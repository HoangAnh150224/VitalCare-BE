package com.vn.vitalcare.care.sharing;

import com.vn.vitalcare.care.alert.AlertDtos;
import com.vn.vitalcare.care.alert.Alerts;
import com.vn.vitalcare.care.appointment.dto.AppointmentResponse;
import com.vn.vitalcare.care.appointment.service.AppointmentService;
import com.vn.vitalcare.care.iomt.VitalViewDtos;
import com.vn.vitalcare.care.iomt.VitalViews;
import com.vn.vitalcare.entity.Customer;
import com.vn.vitalcare.entity.SharingPermission;
import com.vn.vitalcare.entity.VitalSigns;
import com.vn.vitalcare.identity.audit.AuditTrail;
import com.vn.vitalcare.share.security.Permissions;
import com.vn.vitalcare.share.web.ListParams;
import com.vn.vitalcare.share.web.ListResponse;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * BF-06: {@code /api/my_sharing} — the patient's grants; {@code /api/shared_patients} —
 * what caregivers read under them; {@code /api/customers/{id}/caregivers} — the
 * clinic's view of who has access to a patient (Caregiver Access List).
 */
@RestController
public class SharingController {

    private final Sharing sharing;
    private final SharedPatients shared;
    private final VitalViews vitals;
    private final Alerts alerts;
    private final AppointmentService appointments;
    private final SharingPermissionRepository repository;
    private final com.vn.vitalcare.care.customer.service.CustomerService customers;
    private final AuditTrail audit;
    private final Clock clock;

    public SharingController(Sharing sharing, SharedPatients shared, VitalViews vitals, Alerts alerts,
                             AppointmentService appointments, SharingPermissionRepository repository,
                             com.vn.vitalcare.care.customer.service.CustomerService customers, AuditTrail audit,
                             Clock clock) {
        this.sharing = sharing;
        this.shared = shared;
        this.vitals = vitals;
        this.alerts = alerts;
        this.appointments = appointments;
        this.repository = repository;
        this.customers = customers;
        this.audit = audit;
        this.clock = clock;
    }

    private SharingDtos.PermissionResponse dto(SharingPermission p) {
        return SharingDtos.PermissionResponse.of(p, OffsetDateTime.now(clock));
    }

    // ---- the patient's grants ------------------------------------------------------------

    @GetMapping("/api/my_sharing")
    @PreAuthorize("hasAuthority('" + Permissions.MY_SHARING_READ + "')")
    public List<SharingDtos.PermissionResponse> mine() {
        return sharing.mine().stream().map(this::dto).toList();
    }

    @GetMapping("/api/my_sharing/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.MY_SHARING_READ + "')")
    public SharingDtos.PermissionResponse one(@PathVariable Long id) {
        return dto(sharing.get(id));
    }

    @PostMapping("/api/my_sharing")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('" + Permissions.MY_SHARING_WRITE + "')")
    public SharingDtos.PermissionResponse grant(@Valid @RequestBody SharingDtos.GrantRequest request) {
        return dto(sharing.grant(request));
    }

    @PutMapping("/api/my_sharing/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.MY_SHARING_WRITE + "')")
    public SharingDtos.PermissionResponse update(@PathVariable Long id, @Valid @RequestBody SharingDtos.UpdateRequest request) {
        return dto(sharing.update(id, request));
    }

    @PostMapping("/api/my_sharing/{id}/revoke")
    @PreAuthorize("hasAuthority('" + Permissions.MY_SHARING_WRITE + "')")
    public SharingDtos.PermissionResponse revoke(@PathVariable Long id) {
        return dto(sharing.revoke(id));
    }

    // ---- the caregiver's side -------------------------------------------------------------

    @GetMapping("/api/shared_patients")
    @PreAuthorize("hasAuthority('" + Permissions.SHARED_PATIENTS_READ + "')")
    public List<SharingDtos.SharedPatient> sharedWithMe() {
        return shared.mine().stream().map(SharingDtos.SharedPatient::of).toList();
    }

    @GetMapping("/api/shared_patients/{customerId}/vitals/latest")
    @PreAuthorize("hasAuthority('" + Permissions.SHARED_PATIENTS_READ + "')")
    public VitalViewDtos.Latest latest(@PathVariable Long customerId) {
        return vitals.latest(patient(customerId, SharingPermission::isShareVitals, "vitals"));
    }

    @GetMapping("/api/shared_patients/{customerId}/vitals/trend")
    @PreAuthorize("hasAuthority('" + Permissions.SHARED_PATIENTS_READ + "')")
    public VitalViewDtos.Trend trend(@PathVariable Long customerId, @RequestParam(required = false) String from,
                                     @RequestParam(required = false) String to) {
        return vitals.trend(patient(customerId, SharingPermission::isShareVitals, "vitals"), from, to);
    }

    @GetMapping("/api/shared_patients/{customerId}/vitals")
    @PreAuthorize("hasAuthority('" + Permissions.SHARED_PATIENTS_READ + "')")
    public ResponseEntity<List<VitalViewDtos.Reading>> history(@PathVariable Long customerId,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(name = "_start", defaultValue = "0") int start,
            @RequestParam(name = "_end", defaultValue = "20") int end) {
        Page<VitalSigns> page = vitals.history(patient(customerId, SharingPermission::isShareVitals, "vitals"),
                from, to, false, start, end);
        return ListResponse.of(page.getContent().stream().map(VitalViewDtos.Reading::of).toList(), page.getTotalElements());
    }

    @GetMapping("/api/shared_patients/{customerId}/alerts")
    @PreAuthorize("hasAuthority('" + Permissions.SHARED_PATIENTS_READ + "')")
    public List<AlertDtos.AlertResponse> alerts(@PathVariable Long customerId) {
        Customer patient = patient(customerId, SharingPermission::isShareAlerts, "alerts");
        return alerts.forPatient(patient.getId(), 0, 20).getContent().stream().map(AlertDtos.AlertResponse::of).toList();
    }

    @GetMapping("/api/shared_patients/{customerId}/appointments")
    @PreAuthorize("hasAuthority('" + Permissions.SHARED_PATIENTS_READ + "')")
    public List<AppointmentResponse> appointments(@PathVariable Long customerId) {
        Customer patient = patient(customerId, SharingPermission::isShareAppointments, "appointments");
        LinkedMultiValueMap<String, String> q = new LinkedMultiValueMap<>();
        q.add("_start", "0");
        q.add("_end", "20");
        return appointments.listForCustomer(patient, new ListParams(q)).getContent().stream()
                .map(AppointmentResponse::forCustomer).toList();
    }

    /** The grant in force covering that part, re-checked on every read; the look is on record. */
    private Customer patient(Long customerId, java.util.function.Predicate<SharingPermission> part, String what) {
        SharingPermission p = shared.require(customerId, part);
        audit.record("shared_patients", customerId, "view", null, Map.of("part", what, "permission", p.getId()));
        return p.getCustomer();
    }

    // ---- the clinic's view ----------------------------------------------------------------

    /** Caregiver Access List: everybody the patient shared with, in force or not. */
    @GetMapping("/api/customers/{customerId}/caregivers")
    @PreAuthorize("hasAuthority('" + Permissions.CUSTOMERS_READ + "')")
    public List<SharingDtos.PermissionResponse> caregivers(@PathVariable Long customerId) {
        customers.get(customerId);
        return repository.findForProfiles(List.of(customerId)).stream().map(this::dto).toList();
    }
}
