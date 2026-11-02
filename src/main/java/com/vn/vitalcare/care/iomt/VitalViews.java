package com.vn.vitalcare.care.iomt;

import com.vn.vitalcare.care.assignment.repository.DeviceAssignmentRepository;
import com.vn.vitalcare.care.customer.service.CustomerService;
import com.vn.vitalcare.entity.Customer;
import com.vn.vitalcare.entity.DeviceAssignment;
import com.vn.vitalcare.entity.VitalSigns;
import com.vn.vitalcare.share.exception.FieldValidationException;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reading vital signs (FE-04): for staff, the patients they may see; for a
 * patient, their own profiles. Who may be looked at is always settled first —
 * through the customers row-level scope or the account's own profiles — and
 * only then are that one patient's readings read.
 */
@Service
@Transactional(readOnly = true)
public class VitalViews {

    /** The longest window one history or trend request spans. */
    private static final Duration MAX_WINDOW = Duration.ofDays(92);
    private static final int MAX_MONITORED = 500;
    private static final java.time.ZoneId CLINIC_ZONE = java.time.ZoneId.of("Asia/Ho_Chi_Minh");

    private final VitalSignsRepository vitals;
    private final DeviceAssignmentRepository assignments;
    private final CustomerService customers;
    private final com.vn.vitalcare.care.staff.service.ClinicScope clinicScope;
    private final Clock clock;

    public VitalViews(VitalSignsRepository vitals, DeviceAssignmentRepository assignments, CustomerService customers,
                      com.vn.vitalcare.care.staff.service.ClinicScope clinicScope, Clock clock) {
        this.vitals = vitals;
        this.assignments = assignments;
        this.customers = customers;
        this.clinicScope = clinicScope;
        this.clock = clock;
    }

    /** A patient the caller (staff) may see; 404 otherwise, as the customer screens do. */
    public Customer patient(Long customerId) {
        if (customerId == null) {
            throw new FieldValidationException("customerId", "Choose a patient");
        }
        return customers.get(customerId);
    }

    /** One of the caller's own profiles (a patient, or a dependant they manage). */
    public Customer ownProfile(Long profileId) {
        Customer me = customers.getForCurrentUser();
        return profileId == null ? me : customers.ownProfile(me, profileId);
    }

    /** Everybody wearing a device now whom the caller may see, with their latest values. */
    public List<VitalViewDtos.Monitored> monitored() {
        // The caller's own clinic's devices first (all of them for somebody tied to none), then the cap.
        List<DeviceAssignment> worn = assignments.findOpenWithPatients(clinicScope.currentClinicId().orElse(null),
                PageRequest.of(0, MAX_MONITORED));
        if (worn.isEmpty()) {
            return List.of();
        }
        // Through the caller's scope: only the patients the customer screens would show them.
        Map<Long, Customer> visible = customers.getMany(worn.stream().map(a -> a.getCustomer().getId()).distinct().toList())
                .stream().collect(Collectors.toMap(Customer::getId, Function.identity()));
        Map<Long, VitalViewDtos.Latest> latest = latest(visible.keySet());
        return worn.stream()
                .filter(a -> visible.containsKey(a.getCustomer().getId()))
                .map(a -> VitalViewDtos.Monitored.of(a, latest.getOrDefault(a.getCustomer().getId(), VitalViewDtos.Latest.NONE)))
                .toList();
    }

    public VitalViewDtos.Latest latest(Customer patient) {
        return latest(List.of(patient.getId())).getOrDefault(patient.getId(), VitalViewDtos.Latest.NONE);
    }

    private Map<Long, VitalViewDtos.Latest> latest(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return vitals.findLatest(ids).stream()
                .collect(Collectors.toMap(VitalSignsRepository.LatestRow::getCustomerId, VitalViewDtos.Latest::of));
    }

    /** A patient's readings in [from, to), newest first; invalid ones only when asked for. */
    public Page<VitalSigns> history(Customer patient, String from, String to, boolean includeInvalid, int start, int end) {
        OffsetDateTime[] window = window(from, to, Duration.ofDays(7));
        Specification<VitalSigns> spec = (root, q, cb) -> cb.and(
                cb.equal(root.get("customer").get("id"), patient.getId()),
                cb.isNull(root.get("deletedAt")),
                cb.greaterThanOrEqualTo(root.get("measuredAt"), window[0]),
                cb.lessThan(root.get("measuredAt"), window[1]),
                includeInvalid ? cb.conjunction() : cb.equal(root.get("validationStatus"), VitalSigns.VALID));
        int size = Math.min(Math.max(end - start, 1), 200);
        return vitals.findAll(spec, PageRequest.of(Math.max(start, 0) / size, size,
                Sort.by(Sort.Order.desc("measuredAt"), Sort.Order.desc("id"))));
    }

    /** Hourly buckets for up to two days, daily beyond. */
    public VitalViewDtos.Trend trend(Customer patient, String from, String to) {
        OffsetDateTime[] window = window(from, to, Duration.ofDays(7));
        String unit = Duration.between(window[0], window[1]).compareTo(Duration.ofDays(2)) <= 0 ? "hour" : "day";
        OffsetDateTime start = "day".equals(unit)
                ? window[0].atZoneSameInstant(CLINIC_ZONE).toLocalDate().atStartOfDay(CLINIC_ZONE).toOffsetDateTime()
                : window[0];
        return new VitalViewDtos.Trend(unit, start, window[1],
                vitals.findTrend(patient.getId(), unit, start, window[1]).stream()
                        .map(VitalViewDtos.TrendPoint::of).toList());
    }

    /** One measurement, if it is of a patient the check lets through. */
    public VitalSigns one(Long id, Function<Long, Customer> mayRead) {
        VitalSigns v = vitals.findWithDetailsById(id)
                .filter(x -> x.getDeletedAt() == null && x.getCustomer() != null)
                .orElseThrow(() -> new ResourceNotFoundException("Vital sign", id));
        try {
            mayRead.apply(v.getCustomer().getId());
        } catch (ResourceNotFoundException e) {
            throw new ResourceNotFoundException("Vital sign", id);
        }
        return v;
    }

    private OffsetDateTime[] window(String from, String to, Duration fallback) {
        OffsetDateTime end;
        OffsetDateTime start;
        try {
            end = to == null || to.isBlank() ? OffsetDateTime.now(clock) : OffsetDateTime.parse(to.trim());
            start = from == null || from.isBlank() ? end.minus(fallback) : OffsetDateTime.parse(from.trim());
        } catch (DateTimeParseException e) {
            throw new FieldValidationException("from", "Times must be ISO-8601 with an offset");
        }
        if (!start.isBefore(end)) {
            throw new FieldValidationException("from", "The start must be before the end");
        }
        if (Duration.between(start, end).compareTo(MAX_WINDOW) > 0) {
            throw new FieldValidationException("from", "At most 92 days at a time");
        }
        return new OffsetDateTime[] {start, end};
    }
}
