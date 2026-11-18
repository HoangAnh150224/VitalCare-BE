package com.vn.vitalcare.care.results;

import com.vn.vitalcare.care.encounter.repository.ClinicalEncounterRepository;
import com.vn.vitalcare.entity.ClinicalEncounter;
import com.vn.vitalcare.identity.rowlevel.service.RowLevelSecurity;
import com.vn.vitalcare.share.audit.AuditEvent;
import com.vn.vitalcare.share.data.BaseEntitySpecifications;
import com.vn.vitalcare.share.exception.FieldValidationException;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import com.vn.vitalcare.share.security.CurrentUser;
import com.vn.vitalcare.share.security.rowlevel.Action;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * BF-07 Review Service Result and Consultation: once the results are in, the
 * doctor records the final conclusion and what they told the patient. Separate
 * from the visit form and its 24-hour lock — the patient comes back days later.
 */
@Service
public class Consultations {

    private final ClinicalEncounterRepository encounters;
    private final RowLevelSecurity rowLevel;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    /** Lazily: concluding a visit reaches billing and appointments, none of which needs this. */
    private final org.springframework.beans.factory.ObjectProvider<com.vn.vitalcare.care.encounter.service.ClinicalEncounterService> visits;

    public Consultations(ClinicalEncounterRepository encounters, RowLevelSecurity rowLevel,
                         ApplicationEventPublisher events, Clock clock,
                         org.springframework.beans.factory.ObjectProvider<com.vn.vitalcare.care.encounter.service.ClinicalEncounterService> visits) {
        this.visits = visits;
        this.encounters = encounters;
        this.rowLevel = rowLevel;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public Map<String, Object> consult(Long encounterId, String conclusion, String notes) {
        ClinicalEncounter e = encounters.findOne(Specification.allOf(
                        rowLevel.byId(ClinicalEncounter.class, Action.WRITE, encounterId), BaseEntitySpecifications.notDeleted()))
                .orElseThrow(() -> new ResourceNotFoundException("Clinical encounter", encounterId));
        if (conclusion == null || conclusion.isBlank()) {
            throw new FieldValidationException("finalConclusion", "Write the final conclusion");
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        e.consult(conclusion.trim(), notes == null || notes.isBlank() ? null : notes.trim(),
                CurrentUser.id().orElse(null), now);
        encounters.persist(e);
        events.publishEvent(new AuditEvent("clinical_encounters", encounterId, "consult", null, Map.of("at", now.toString())));
        // The patient is back with their results: the doctor's conclusion on them is what ends
        // the visit — no separate diagnosis first. Concluding still needs every result read.
        if (e.getStatus() != com.vn.vitalcare.entity.EncounterStatus.COMPLETED) {
            encounters.flush();
            visits.getObject().complete(encounterId);
        }
        Map<String, Object> body = new HashMap<>();
        body.put("id", encounterId);
        body.put("finalConclusion", e.getFinalConclusion());
        body.put("consultationNotes", e.getConsultationNotes());
        body.put("consultedAt", e.getConsultedAt());
        return body;
    }
}
