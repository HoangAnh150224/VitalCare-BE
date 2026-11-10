package com.vn.vitalcare.care.sharing;

import com.vn.vitalcare.care.customer.service.CustomerService;
import com.vn.vitalcare.entity.Customer;
import com.vn.vitalcare.entity.SharingPermission;
import com.vn.vitalcare.identity.user.entity.User;
import com.vn.vitalcare.identity.user.service.UserService;
import com.vn.vitalcare.share.audit.AuditEvent;
import com.vn.vitalcare.share.exception.ConflictException;
import com.vn.vitalcare.share.exception.FieldValidationException;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import com.vn.vitalcare.share.phone.PhoneNumbers;
import com.vn.vitalcare.share.security.CurrentUser;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A patient sharing their health data with caregivers (BF-06 Sharing
 * Permission): with an account the caregiver already has, for the parts the
 * patient picks, until a date if they like, with an emergency number; at most
 * two in force at once (BR-22); revoked at any time, which ends access at once.
 */
@Service
@Transactional(readOnly = true)
public class Sharing {

    /** BR-22: at most this many caregivers in force per patient. */
    static final int MAX_ACTIVE = 2;

    private final SharingPermissionRepository permissions;
    private final CustomerService customers;
    private final UserService users;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public Sharing(SharingPermissionRepository permissions, CustomerService customers, UserService users,
                   ApplicationEventPublisher events, Clock clock) {
        this.permissions = permissions;
        this.customers = customers;
        this.users = users;
        this.events = events;
        this.clock = clock;
    }

    /** Every grant of the account's profiles — in force, expired and revoked: list and history. */
    public List<SharingPermission> mine() {
        List<Long> ids = customers.profilesOf(customers.getForCurrentUser()).stream().map(Customer::getId).toList();
        return permissions.findForProfiles(ids);
    }

    public SharingPermission get(Long id) {
        return own(id);
    }

    @Transactional
    public SharingPermission grant(SharingDtos.GrantRequest r) {
        Customer me = customers.getForCurrentUser();
        Customer patient = r.profileId() == null ? me : customers.ownProfile(me, r.profileId());
        User caregiver = users.findForAuthentication(r.caregiverPhone())
                .filter(User::canSignIn)
                .filter(candidate -> candidate.getDeletedAt() == null)
                .filter(candidate -> candidate.roleCodes().stream().anyMatch(Set.of("GUEST", "PATIENT")::contains))
                .orElseThrow(() -> new FieldValidationException("caregiverPhone",
                        "No active Guest or Patient account uses that number; ask the caregiver to register first"));
        if (CurrentUser.id().map(caregiver.getId()::equals).orElse(false)) {
            throw new FieldValidationException("caregiverPhone", "Choose somebody other than yourself");
        }
        String emergency = PhoneNumbers.normalize(r.emergencyPhone())
                .orElseThrow(() -> new FieldValidationException("emergencyPhone", "Enter a Vietnamese phone number"));
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (r.expiresAt() != null && !r.expiresAt().isAfter(now)) {
            throw new FieldValidationException("expiresAt", "The end of access must be in the future");
        }
        permissions.lockPatient(patient.getId());
        List<SharingPermission> inForce = permissions.findInForceOf(patient.getId(), now);
        if (inForce.stream().anyMatch(p -> p.getCaregiver().getId().equals(caregiver.getId()))) {
            throw new ConflictException("That caregiver already has access; change their access instead");
        }
        if (inForce.size() >= MAX_ACTIVE) {
            throw new ConflictException("At most two caregivers may have access at a time; revoke one first");
        }
        SharingPermission p = new SharingPermission();
        p.setCustomer(patient);
        p.setCaregiver(caregiver);
        p.setShareVitals(r.shareVitals() == null || r.shareVitals());
        p.setShareAlerts(r.shareAlerts() == null || r.shareAlerts());
        p.setShareAppointments(Boolean.TRUE.equals(r.shareAppointments()));
        p.setEmergencyPhone(emergency);
        p.setExpiresAt(r.expiresAt());
        p.setNote(r.note() == null || r.note().isBlank() ? null : r.note().trim());
        p.setStatus(SharingPermission.ACTIVE);
        SharingPermission saved = permissions.save(p);
        events.publishEvent(new AuditEvent("sharing_permissions", saved.getId(), "grant", null, state(saved)));
        return saved;
    }

    @Transactional
    public SharingPermission update(Long id, SharingDtos.UpdateRequest r) {
        SharingPermission p = own(id);
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (!p.inForce(now)) {
            throw new ConflictException("Only access in force can be changed; grant it again instead");
        }
        Map<String, Object> before = state(p);
        if (r.shareVitals() != null) {
            p.setShareVitals(r.shareVitals());
        }
        if (r.shareAlerts() != null) {
            p.setShareAlerts(r.shareAlerts());
        }
        if (r.shareAppointments() != null) {
            p.setShareAppointments(r.shareAppointments());
        }
        if (r.emergencyPhone() != null) {
            p.setEmergencyPhone(PhoneNumbers.normalize(r.emergencyPhone())
                    .orElseThrow(() -> new FieldValidationException("emergencyPhone", "Enter a Vietnamese phone number")));
        }
        if (Boolean.TRUE.equals(r.clearExpiry())) {
            p.setExpiresAt(null);
        } else if (r.expiresAt() != null) {
            if (!r.expiresAt().isAfter(now)) {
                throw new FieldValidationException("expiresAt", "The end of access must be in the future");
            }
            p.setExpiresAt(r.expiresAt());
        }
        if (r.note() != null) {
            p.setNote(r.note().isBlank() ? null : r.note().trim());
        }
        SharingPermission saved = permissions.save(p);
        events.publishEvent(new AuditEvent("sharing_permissions", id, "update", before, state(saved)));
        return saved;
    }

    /** BR-23: revoked, access ends at once — every caregiver read checks it. */
    @Transactional
    public SharingPermission revoke(Long id) {
        SharingPermission p = own(id);
        if (SharingPermission.REVOKED.equals(p.getStatus())) {
            throw new ConflictException("Already revoked");
        }
        p.setStatus(SharingPermission.REVOKED);
        p.setRevokedAt(OffsetDateTime.now(clock));
        p.setRevokedById(CurrentUser.id().orElse(null));
        SharingPermission saved = permissions.save(p);
        events.publishEvent(new AuditEvent("sharing_permissions", id, "revoke", null, state(saved)));
        return saved;
    }

    /** A grant of one of the caller's own profiles; anybody else's is not found. */
    private SharingPermission own(Long id) {
        List<Long> mine = customers.profilesOf(customers.getForCurrentUser()).stream().map(Customer::getId).toList();
        return permissions.findWithParties(id)
                .filter(p -> p.getDeletedAt() == null && mine.contains(p.getCustomer().getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Sharing permission", id));
    }

    private static Map<String, Object> state(SharingPermission p) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("caregiverUserId", p.getCaregiver().getId());
        m.put("vitals", p.isShareVitals());
        m.put("alerts", p.isShareAlerts());
        m.put("appointments", p.isShareAppointments());
        m.put("expiresAt", String.valueOf(p.getExpiresAt()));
        m.put("status", p.getStatus());
        return m;
    }
}
