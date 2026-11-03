package com.vn.vitalcare.care.results;

import com.vn.vitalcare.care.staff.service.EmployeeService;
import com.vn.vitalcare.entity.Employee;
import com.vn.vitalcare.entity.ProfessionalLicense;
import com.vn.vitalcare.identity.rowlevel.service.RowLevelSecurity;
import com.vn.vitalcare.share.audit.AuditEvent;
import com.vn.vitalcare.share.data.BaseEntitySpecifications;
import com.vn.vitalcare.share.exception.FieldValidationException;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import com.vn.vitalcare.share.security.CurrentUser;
import com.vn.vitalcare.share.security.rowlevel.Action;
import com.vn.vitalcare.share.storage.FileStorage;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.Resource;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * FE-17 Manage Own Professional License and Certificate: a member of staff
 * keeps theirs, with the scanned proof (PDF or image); the clinic
 * administrator reads the clinic's and sees which expire within 60 days.
 */
@Service
@Transactional(readOnly = true)
public class Licenses {

    public static final int WARN_DAYS = 60;
    private static final long MAX_FILE = 10L * 1024 * 1024;

    public record LicenseRequest(@NotBlank(message = "Choose license or certificate") String kind,
                                 @NotBlank(message = "Name it") @Size(max = 255) String title,
                                 @NotBlank(message = "Enter its number") @Size(max = 64) String licenseNumber,
                                 @NotBlank(message = "Enter who issued it") @Size(max = 255) String issuer,
                                 @NotNull(message = "Enter when it was issued") LocalDate issuedOn,
                                 LocalDate expiresOn) {
    }

    public record StoredFile(String name, String type, long size, Resource content) {
    }

    private final ProfessionalLicenseRepository licenses;
    private final EmployeeService employees;
    private final FileStorage storage;
    private final RowLevelSecurity rowLevel;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public Licenses(ProfessionalLicenseRepository licenses, EmployeeService employees, FileStorage storage,
                    RowLevelSecurity rowLevel, ApplicationEventPublisher events, Clock clock) {
        this.licenses = licenses;
        this.employees = employees;
        this.storage = storage;
        this.rowLevel = rowLevel;
        this.events = events;
        this.clock = clock;
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh")));
    }

    public List<ProfessionalLicense> own() {
        return licenses.findOwn(me().getId());
    }

    /** The clinic's licenses; {@code expiring} keeps the ones expiring within 60 days or expired. */
    public List<ProfessionalLicense> clinic(boolean expiring) {
        LocalDate limit = today().plusDays(WARN_DAYS);
        return licenses.findAll(Specification.allOf(rowLevel.scope(ProfessionalLicense.class, Action.READ),
                BaseEntitySpecifications.notDeleted(),
                (r, q, cb) -> {
                    q.orderBy(cb.asc(r.get("expiresOn")));
                    return expiring ? cb.and(cb.isNotNull(r.get("expiresOn")), cb.lessThanOrEqualTo(r.get("expiresOn"), limit))
                            : cb.conjunction();
                }));
    }

    @Transactional
    public ProfessionalLicense create(LicenseRequest r) {
        Employee me = me();
        ProfessionalLicense l = new ProfessionalLicense();
        l.setEmployee(me);
        l.setClinicId(me.getClinic() == null ? null : me.getClinic().getClinicId());
        apply(l, r);
        ProfessionalLicense saved = licenses.persist(l);
        events.publishEvent(new AuditEvent("licenses", saved.getId(), "create", null, Map.of("number", r.licenseNumber())));
        return saved;
    }

    @Transactional
    public ProfessionalLicense update(Long id, LicenseRequest r) {
        ProfessionalLicense l = mine(id);
        apply(l, r);
        events.publishEvent(new AuditEvent("licenses", id, "update", null, Map.of("number", r.licenseNumber())));
        return licenses.persist(l);
    }

    @Transactional
    public void remove(Long id) {
        ProfessionalLicense l = mine(id);
        l.markDeleted(CurrentUser.id().orElse(null), clock.instant());
        licenses.persist(l);
    }

    /** The scanned proof: PDF, PNG or JPEG, at most 10 MB, recognised by its bytes. */
    @Transactional
    public ProfessionalLicense attach(Long id, MultipartFile file) {
        ProfessionalLicense l = mine(id);
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new FieldValidationException("file", "The file could not be read");
        }
        if (bytes.length == 0 || bytes.length > MAX_FILE) {
            throw new FieldValidationException("file", "A file of at most 10 MB");
        }
        String type = sniff(bytes);
        if (type == null) {
            throw new FieldValidationException("file", "Only PDF, PNG or JPEG");
        }
        String previous = l.getFileKey();
        String stored = storage.store(new ByteArrayInputStream(bytes));
        // The replaced scan goes once the new one is committed; the new one, if it never is.
        afterCompletion(committed -> storage.delete(committed ? previous : stored));
        l.setFileKey(stored);
        String name = file.getOriginalFilename() == null ? "giay-phep" : file.getOriginalFilename().replaceAll("[\\\\/:*?\"<>|]", "_");
        l.setFileName(name.length() > 255 ? name.substring(0, 255) : name);
        l.setFileType(type);
        l.setFileSize((long) bytes.length);
        return licenses.persist(l);
    }

    private static void afterCompletion(java.util.function.Consumer<Boolean> action) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        try {
                            action.accept(status == STATUS_COMMITTED);
                        } catch (RuntimeException e) {
                            // A file left behind is not worth failing an answer already given.
                            org.slf4j.LoggerFactory.getLogger(Licenses.class).warn("Could not remove a license scan", e);
                        }
                    }
                });
    }

    public StoredFile ownFile(Long id) {
        return file(mine(id));
    }

    public StoredFile clinicFile(Long id) {
        return file(licenses.findOne(Specification.allOf(rowLevel.byId(ProfessionalLicense.class, Action.READ, id),
                        BaseEntitySpecifications.notDeleted()))
                .orElseThrow(() -> new ResourceNotFoundException("License", id)));
    }

    private StoredFile file(ProfessionalLicense l) {
        if (l.getFileKey() == null) {
            throw new ResourceNotFoundException("License file", l.getId());
        }
        return new StoredFile(l.getFileName(), l.getFileType(), l.getFileSize(), storage.load(l.getFileKey()));
    }

    private void apply(ProfessionalLicense l, LicenseRequest r) {
        String kind = r.kind().trim().toUpperCase(Locale.ROOT);
        if (!ProfessionalLicense.LICENSE.equals(kind) && !ProfessionalLicense.CERTIFICATE.equals(kind)) {
            throw new FieldValidationException("kind", "Choose license or certificate");
        }
        if (r.issuedOn().isAfter(today())) {
            throw new FieldValidationException("issuedOn", "It cannot be issued in the future");
        }
        if (r.expiresOn() != null && !r.expiresOn().isAfter(r.issuedOn())) {
            throw new FieldValidationException("expiresOn", "It must expire after it was issued");
        }
        l.setKind(kind);
        l.setTitle(r.title().trim());
        l.setLicenseNumber(r.licenseNumber().trim());
        l.setIssuer(r.issuer().trim());
        l.setIssuedOn(r.issuedOn());
        l.setExpiresOn(r.expiresOn());
    }

    private ProfessionalLicense mine(Long id) {
        Long me = me().getId();
        return licenses.findOwn(me).stream().filter(l -> l.getId().equals(id)).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("License", id));
    }

    private Employee me() {
        return employees.getForCurrentUser();
    }

    static String sniff(byte[] b) {
        if (b.length >= 4 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F') {
            return "application/pdf";
        }
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "image/png";
        }
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        return null;
    }
}
