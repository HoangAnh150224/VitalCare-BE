package com.vn.vitalcare.care.serviceorder;

import com.vn.vitalcare.care.appointment.service.VisitEvents;
import com.vn.vitalcare.care.billing.catalog.MedicalServiceCatalog;
import com.vn.vitalcare.care.examroom.service.ExamRoomEvents;
import com.vn.vitalcare.care.encounter.repository.ClinicalEncounterRepository;
import com.vn.vitalcare.care.staff.service.EmployeeService;
import com.vn.vitalcare.entity.ClinicalEncounter;
import com.vn.vitalcare.entity.EncounterStatus;
import com.vn.vitalcare.entity.MedicalService;
import com.vn.vitalcare.entity.ServiceOrder;
import com.vn.vitalcare.entity.ServiceOrderFile;
import com.vn.vitalcare.entity.ServiceOrderStatus;
import com.vn.vitalcare.entity.StaffType;
import com.vn.vitalcare.identity.rowlevel.service.RowLevelSecurity;
import com.vn.vitalcare.identity.user.service.UserService;
import com.vn.vitalcare.share.data.BaseEntitySpecifications;
import com.vn.vitalcare.share.exception.ConflictException;
import com.vn.vitalcare.share.exception.FieldValidationException;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import com.vn.vitalcare.share.security.CurrentUser;
import com.vn.vitalcare.share.security.rowlevel.Action;
import com.vn.vitalcare.share.storage.FileStorage;
import com.vn.vitalcare.share.storage.UploadedFiles;
import com.vn.vitalcare.share.web.ListParams;
import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Services ordered during an encounter and their results (Report 1 FE-22).
 *
 * <p>A doctor orders while the encounter is open; any clinical member of
 * staff of that clinic performs it and records the result, with files; the
 * doctor reviews it before concluding. Everything goes through the
 * {@code service_orders} clinic scope: another clinic's order is not found.
 *
 * <p>The encounter is read through its own repository and scope, not its
 * service, because the encounter service asks this one (through the
 * repository) whether it may conclude.
 */
@Service
@Transactional(readOnly = true)
public class ServiceOrders {

    static final long MAX_FILE_BYTES = 10L * 1024 * 1024;

    private static final Set<String> SORTABLE = Set.of("id", "createdAt", "status", "expectedResultAt");
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Order.asc("expectedResultAt"), Sort.Order.asc("id"));

    private final ServiceOrderRepository orders;
    private final ServiceOrderFileRepository files;
    private final ClinicalEncounterRepository encounters;
    private final MedicalServiceCatalog catalog;
    private final EmployeeService employeeService;
    private final UserService userService;
    private final FileStorage storage;
    private final RowLevelSecurity rowLevel;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    public ServiceOrders(ServiceOrderRepository orders,
                         ServiceOrderFileRepository files,
                         ClinicalEncounterRepository encounters,
                         MedicalServiceCatalog catalog,
                         EmployeeService employeeService,
                         UserService userService,
                         FileStorage storage,
                         RowLevelSecurity rowLevel,
                         Clock clock,
                         ApplicationEventPublisher events) {
        this.orders = orders;
        this.files = files;
        this.encounters = encounters;
        this.catalog = catalog;
        this.employeeService = employeeService;
        this.userService = userService;
        this.storage = storage;
        this.rowLevel = rowLevel;
        this.clock = clock;
        this.events = events;
    }

    public Page<ServiceOrder> list(ListParams params) {
        return orders.findAll(
                rowLevel.scope(ServiceOrder.class, Action.READ).and(ServiceOrderSpecifications.from(params)),
                params.pageable(SORTABLE, DEFAULT_SORT));
    }

    public ServiceOrder get(Long id) {
        return find(id, Action.READ);
    }

    /** An encounter's orders, for its page; the encounter itself through its read scope. */
    public List<ServiceOrder> forEncounter(Long encounterId) {
        encounter(encounterId, Action.READ);
        return orders.findByEncounter(encounterId);
    }

    @Transactional
    public ServiceOrder order(Long encounterId, ServiceOrderRequests.Order request) {
        requireDoctor("Only a doctor orders services");
        // Against the visit being concluded at the same moment; see ClinicalEncounterRepository.lockRow.
        encounters.lockRow(encounterId);
        ClinicalEncounter encounter = encounter(encounterId, Action.WRITE);
        if (encounter.getStatus() == EncounterStatus.COMPLETED) {
            throw new ConflictException("This encounter is concluded; services can no longer be ordered in it");
        }
        MedicalService service = catalog.orderable(request.serviceId());
        OffsetDateTime now = OffsetDateTime.now(clock);

        ServiceOrder order = new ServiceOrder();
        order.setEncounter(encounter);
        order.setCustomer(encounter.getCustomer());
        order.setClinic(encounter.getClinic());
        order.setService(service);
        order.setUnitPrice(service.getPrice());
        order.setInstruction(blankToNull(request.instruction()));
        order.setExpectedResultAt(now.plus(Duration.ofMinutes(service.getTurnaroundMinutes())));

        ServiceOrder saved = orders.persist(order);
        orders.flush();
        rowLevel.checkWritable(saved);
        return saved;
    }

    @Transactional
    public ServiceOrder start(Long id) {
        ServiceOrder order = find(id, Action.WRITE);
        if (order.getStatus() != ServiceOrderStatus.ORDERED) {
            throw new ConflictException("Only an order not yet started can be started");
        }
        order.start(CurrentUser.id().orElse(null), OffsetDateTime.now(clock));
        ServiceOrder saved = orders.persist(order);
        events.publishEvent(new ExamRoomEvents.BoardChanged(saved.getClinicId()));
        return saved;
    }

    /** The result as text. Recording it again before the doctor has reviewed corrects it. */
    @Transactional
    public ServiceOrder recordResult(Long id, ServiceOrderRequests.Result request) {
        ServiceOrder order = find(id, Action.WRITE);
        if (order.getStatus() == ServiceOrderStatus.REVIEWED || order.getStatus() == ServiceOrderStatus.CANCELLED) {
            throw new ConflictException("This order is closed; its result can no longer change");
        }
        order.setResultText(request.resultText().trim());
        boolean first = order.getStatus() != ServiceOrderStatus.RESULT_READY;
        if (first) {
            order.recordResult(CurrentUser.id().orElse(null), OffsetDateTime.now(clock));
        }
        ServiceOrder saved = orders.persist(order);
        if (first) {
            changed(saved);
        }
        return saved;
    }

    @Transactional
    public ServiceOrder attach(Long id, MultipartFile upload) {
        ServiceOrder order = find(id, Action.WRITE);
        if (order.getStatus() == ServiceOrderStatus.REVIEWED || order.getStatus() == ServiceOrderStatus.CANCELLED) {
            throw new ConflictException("This order is closed; files can no longer be added");
        }
        byte[] bytes = readChecked(upload);
        String key = storage.store(new ByteArrayInputStream(bytes));
        String name = safeName(upload.getOriginalFilename());
        ServiceOrderFile file = new ServiceOrderFile(order, name, upload.getContentType(), bytes.length, key);
        order.getFiles().add(files.save(file));
        return order;
    }

    /** A file of an order the caller may read, with its bytes. */
    public StoredFile file(Long id, Long fileId) {
        find(id, Action.READ);
        ServiceOrderFile file = files.findOfOrder(id, fileId)
                .orElseThrow(() -> new ResourceNotFoundException("File", fileId));
        return new StoredFile(file.getOriginalName(), file.getContentType(), file.getSizeBytes(),
                storage.load(file.getStorageKey()));
    }

    @Transactional
    public ServiceOrder review(Long id) {
        requireDoctor("Only a doctor reviews a result");
        ServiceOrder order = find(id, Action.WRITE);
        if (order.getStatus() != ServiceOrderStatus.RESULT_READY) {
            throw new ConflictException("Only a result that is in can be reviewed");
        }
        order.review(CurrentUser.id().orElse(null), OffsetDateTime.now(clock));
        return orders.persist(order);
    }

    @Transactional
    public ServiceOrder cancel(Long id, ServiceOrderRequests.Cancel request) {
        requireDoctor("Only a doctor cancels an order");
        ServiceOrder order = find(id, Action.WRITE);
        if (order.getStatus() != ServiceOrderStatus.ORDERED && order.getStatus() != ServiceOrderStatus.IN_PROGRESS) {
            throw new ConflictException("Only an order without a result can be cancelled");
        }
        if (order.getEncounter().getStatus() == EncounterStatus.COMPLETED) {
            throw new ConflictException("This encounter is concluded; its orders can no longer change");
        }
        order.cancel(OffsetDateTime.now(clock), request.reason().trim());
        ServiceOrder saved = orders.persist(order);
        changed(saved);
        return saved;
    }

    /** The visit this order is for may be away waiting on it; and the boards and portals look again. */
    private void changed(ServiceOrder order) {
        var appointment = order.getEncounter().getAppointment();
        events.publishEvent(new VisitEvents.OrdersChanged(appointment == null ? null : appointment.getId()));
        events.publishEvent(new ExamRoomEvents.BoardChanged(order.getClinicId()));
    }

    /** Full names of everybody an order mentions, for responses; one query for any number of orders. */
    public Map<Long, String> namesFor(Collection<ServiceOrder> list) {
        List<Long> ids = list.stream()
                .flatMap(order -> Arrays.stream(new Long[] {
                        order.getCreatedById(), order.getPerformedById(), order.getReviewedById()}))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, String> names = new HashMap<>();
        if (!ids.isEmpty()) {
            userService.getMany(ids).forEach(user -> names.put(user.getId(), user.getFullName()));
        }
        return names;
    }

    public Map<String, Boolean> capabilities(ServiceOrder order) {
        return rowLevel.capabilities(order);
    }

    /** Bytes of a stored file, for a download. */
    public record StoredFile(String name, String contentType, long sizeBytes, Resource content) {
    }

    /** What a result may carry: lab sheets and images, nothing executable or opaque. */
    private byte[] readChecked(MultipartFile upload) {
        return UploadedFiles.readChecked(upload, UploadedFiles.PDF_PNG_JPEG, MAX_FILE_BYTES,
                "A file can be at most 10 MB", "Only PDF, PNG and JPEG files are accepted");
    }

    /** The uploaded name, kept only for the download's file name: no path, no control characters. */
    private static String safeName(String raw) {
        String name = raw == null ? "" : raw.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}\"]", "").trim();
        if (name.isEmpty()) {
            name = "ket-qua";
        }
        return name.length() > 200 ? name.substring(name.length() - 200) : name;
    }

    private void requireDoctor(String message) {
        if (employeeService.getForCurrentUser().getStaffType() != StaffType.DOCTOR) {
            throw new AccessDeniedException(message);
        }
    }

    private ClinicalEncounter encounter(Long id, Action action) {
        return encounters.findOne(Specification.allOf(
                        rowLevel.byId(ClinicalEncounter.class, action, id), BaseEntitySpecifications.notDeleted()))
                .orElseThrow(() -> new ResourceNotFoundException("Encounter", id));
    }

    private ServiceOrder find(Long id, Action action) {
        return orders.findOne(Specification.allOf(
                        rowLevel.byId(ServiceOrder.class, action, id), BaseEntitySpecifications.notDeleted()))
                .orElseThrow(() -> new ResourceNotFoundException("Service order", id));
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
