package com.vn.vitalcare.care.results;

import com.vn.vitalcare.entity.PatientFeedback;
import com.vn.vitalcare.entity.ProfessionalLicense;
import com.vn.vitalcare.entity.ResultReturn;
import com.vn.vitalcare.share.security.Permissions;
import com.vn.vitalcare.share.web.ListParams;
import com.vn.vitalcare.share.web.ListResponse;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Phase 7: {@code /api/result_returns} (+ {@code /api/my_result_returns}),
 * {@code /api/clinical_encounters/{id}/consultation}, {@code /api/feedback}
 * (+ {@code /api/my_feedback}), {@code /api/licenses} (+ {@code /api/my_licenses}).
 */
@RestController
public class ResultsController {

    private final ResultReturns returns;
    private final Consultations consultations;
    private final Feedback feedback;
    private final Licenses licenses;

    public ResultsController(ResultReturns returns, Consultations consultations, Feedback feedback, Licenses licenses) {
        this.returns = returns;
        this.consultations = consultations;
        this.feedback = feedback;
        this.licenses = licenses;
    }

    // ---- result returns ---------------------------------------------------------------

    public record ReturnResponse(Long id, Long appointmentId, Long customerId, String customerName,
                                 OffsetDateTime scheduledAt, String note, String status, OffsetDateTime arrivedAt,
                                 OffsetDateTime missedAt) {
        static ReturnResponse of(ResultReturn r) {
            return new ReturnResponse(r.getId(), r.getAppointment().getId(), r.getCustomer().getId(),
                    r.getCustomer().getFullName(), r.getScheduledAt(), r.getNote(), r.getStatus(), r.getArrivedAt(),
                    r.getMissedAt());
        }
    }

    public record ScheduleRequest(Long appointmentId, Long encounterId, OffsetDateTime scheduledAt, String note) {
    }

    @GetMapping("/api/result_returns")
    @PreAuthorize("hasAuthority('" + Permissions.RESULT_RETURNS_READ + "')")
    public ResponseEntity<List<ReturnResponse>> returns(@RequestParam MultiValueMap<String, String> query) {
        Page<ResultReturn> page = returns.list(new ListParams(query));
        return ListResponse.of(page.getContent().stream().map(ReturnResponse::of).toList(), page.getTotalElements());
    }

    @PostMapping("/api/result_returns")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('" + Permissions.RESULT_RETURNS_WRITE + "')")
    public ReturnResponse schedule(@RequestBody ScheduleRequest r) {
        if (r.encounterId() != null) {
            return ReturnResponse.of(returns.scheduleForEncounter(r.encounterId(), r.scheduledAt(), r.note()));
        }
        if (r.appointmentId() == null) {
            throw new com.vn.vitalcare.share.exception.FieldValidationException("appointmentId", "Choose the visit");
        }
        return ReturnResponse.of(returns.schedule(r.appointmentId(), r.scheduledAt(), r.note()));
    }

    @PostMapping("/api/result_returns/{id}/arrive")
    @PreAuthorize("hasAuthority('" + Permissions.RESULT_RETURNS_WRITE + "')")
    public ReturnResponse arrive(@PathVariable Long id) {
        return ReturnResponse.of(returns.arrive(id));
    }

    @PostMapping("/api/result_returns/{id}/reschedule")
    @PreAuthorize("hasAuthority('" + Permissions.RESULT_RETURNS_WRITE + "')")
    public ReturnResponse reschedule(@PathVariable Long id, @RequestBody ScheduleRequest r) {
        return ReturnResponse.of(returns.reschedule(id, r.scheduledAt()));
    }

    @PostMapping("/api/result_returns/{id}/cancel")
    @PreAuthorize("hasAuthority('" + Permissions.RESULT_RETURNS_WRITE + "')")
    public ReturnResponse cancel(@PathVariable Long id) {
        return ReturnResponse.of(returns.cancel(id));
    }

    @GetMapping("/api/my_result_returns")
    @PreAuthorize("hasAuthority('" + Permissions.MY_RESULT_RETURNS_READ + "')")
    public List<ReturnResponse> myReturns() {
        return returns.own().stream().map(ReturnResponse::of).toList();
    }

    // ---- consultation -------------------------------------------------------------------

    public record ConsultationRequest(String finalConclusion, String consultationNotes) {
    }

    @PostMapping("/api/clinical_encounters/{id}/consultation")
    @PreAuthorize("hasAuthority('" + Permissions.CLINICAL_ENCOUNTERS_CONCLUDE + "')")
    public Map<String, Object> consult(@PathVariable Long id, @RequestBody ConsultationRequest r) {
        return consultations.consult(id, r.finalConclusion(), r.consultationNotes());
    }

    // ---- feedback -----------------------------------------------------------------------

    public record FeedbackResponse(Long id, Long appointmentId, String appointmentDate, Long customerId,
                                   String customerName, String doctorName, int rating, String comment,
                                   OffsetDateTime createdAt) {
        static FeedbackResponse of(PatientFeedback f) {
            var doctor = f.getAppointment().getEmployee();
            return new FeedbackResponse(f.getId(), f.getAppointment().getId(),
                    String.valueOf(f.getAppointment().getAppointmentDate()), f.getCustomer().getId(),
                    f.getCustomer().getFullName(),
                    doctor == null || doctor.getUser() == null ? null : doctor.getUser().getFullName(),
                    f.getRating(), f.getComment(),
                    f.getCreatedAt() == null ? null : f.getCreatedAt().atOffset(java.time.ZoneOffset.UTC));
        }
    }

    public record FeedbackRequest(Long appointmentId, Integer rating, String comment) {
    }

    @GetMapping("/api/my_feedback")
    @PreAuthorize("hasAuthority('" + Permissions.MY_FEEDBACK_READ + "')")
    public List<FeedbackResponse> myFeedback() {
        return feedback.own().stream().map(FeedbackResponse::of).toList();
    }

    @PostMapping("/api/my_feedback")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('" + Permissions.MY_FEEDBACK_WRITE + "')")
    public FeedbackResponse give(@RequestBody FeedbackRequest r) {
        if (r.appointmentId() == null) {
            throw new com.vn.vitalcare.share.exception.FieldValidationException("appointmentId", "Choose the visit");
        }
        return FeedbackResponse.of(feedback.give(r.appointmentId(), r.rating(), r.comment()));
    }

    @PutMapping("/api/my_feedback/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.MY_FEEDBACK_WRITE + "')")
    public FeedbackResponse edit(@PathVariable Long id, @RequestBody FeedbackRequest r) {
        return FeedbackResponse.of(feedback.edit(id, r.rating(), r.comment()));
    }

    @GetMapping("/api/feedback")
    @PreAuthorize("hasAuthority('" + Permissions.FEEDBACK_READ + "')")
    public ResponseEntity<List<FeedbackResponse>> feedback(@RequestParam MultiValueMap<String, String> query) {
        Page<PatientFeedback> page = feedback.list(new ListParams(query));
        return ListResponse.of(page.getContent().stream().map(FeedbackResponse::of).toList(), page.getTotalElements());
    }

    @GetMapping("/api/feedback/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.FEEDBACK_READ + "')")
    public FeedbackResponse oneFeedback(@PathVariable Long id) {
        return FeedbackResponse.of(feedback.get(id));
    }

    // ---- licenses -----------------------------------------------------------------------

    public record LicenseResponse(Long id, Long employeeId, String holderName, String kind, String title,
                                  String licenseNumber, String issuer, LocalDate issuedOn, LocalDate expiresOn,
                                  String validity, boolean hasFile, String fileName) {
        static LicenseResponse of(ProfessionalLicense l, LocalDate today) {
            return new LicenseResponse(l.getId(), l.getEmployee().getId(),
                    l.getEmployee().getUser() == null ? null : l.getEmployee().getUser().getFullName(), l.getKind(),
                    l.getTitle(), l.getLicenseNumber(), l.getIssuer(), l.getIssuedOn(), l.getExpiresOn(),
                    l.validity(today, Licenses.WARN_DAYS), l.getFileKey() != null, l.getFileName());
        }
    }

    @GetMapping("/api/my_licenses")
    @PreAuthorize("hasAuthority('" + Permissions.MY_LICENSES_READ + "')")
    public List<LicenseResponse> myLicenses() {
        LocalDate today = licenses.today();
        return licenses.own().stream().map(l -> LicenseResponse.of(l, today)).toList();
    }

    @PostMapping("/api/my_licenses")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('" + Permissions.MY_LICENSES_WRITE + "')")
    public LicenseResponse addLicense(@Valid @RequestBody Licenses.LicenseRequest r) {
        return LicenseResponse.of(licenses.create(r), licenses.today());
    }

    @PutMapping("/api/my_licenses/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.MY_LICENSES_WRITE + "')")
    public LicenseResponse updateLicense(@PathVariable Long id, @Valid @RequestBody Licenses.LicenseRequest r) {
        return LicenseResponse.of(licenses.update(id, r), licenses.today());
    }

    @DeleteMapping("/api/my_licenses/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.MY_LICENSES_WRITE + "')")
    public ResponseEntity<Void> removeLicense(@PathVariable Long id) {
        licenses.remove(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/api/my_licenses/{id}/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('" + Permissions.MY_LICENSES_WRITE + "')")
    public LicenseResponse attach(@PathVariable Long id, @RequestPart("file") MultipartFile file) {
        return LicenseResponse.of(licenses.attach(id, file), licenses.today());
    }

    @GetMapping("/api/my_licenses/{id}/file")
    @PreAuthorize("hasAuthority('" + Permissions.MY_LICENSES_READ + "')")
    public ResponseEntity<org.springframework.core.io.Resource> ownFile(@PathVariable Long id) {
        return download(licenses.ownFile(id));
    }

    @GetMapping("/api/licenses")
    @PreAuthorize("hasAuthority('" + Permissions.LICENSES_READ + "')")
    public List<LicenseResponse> clinicLicenses(@RequestParam(required = false) String expiring) {
        LocalDate today = licenses.today();
        return licenses.clinic("true".equals(expiring)).stream().map(l -> LicenseResponse.of(l, today)).toList();
    }

    @GetMapping("/api/licenses/{id}/file")
    @PreAuthorize("hasAuthority('" + Permissions.LICENSES_READ + "')")
    public ResponseEntity<org.springframework.core.io.Resource> clinicFile(@PathVariable Long id) {
        return download(licenses.clinicFile(id));
    }

    private static ResponseEntity<org.springframework.core.io.Resource> download(Licenses.StoredFile f) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(f.type()))
                .contentLength(f.size())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(f.name(), java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(f.content());
    }
}
