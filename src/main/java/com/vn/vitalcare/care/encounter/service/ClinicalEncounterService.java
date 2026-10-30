package com.vn.vitalcare.care.encounter.service;

import com.vn.vitalcare.care.encounter.dto.EncounterRequest;
import com.vn.vitalcare.entity.ClinicalEncounter;
import com.vn.vitalcare.share.web.ListParams;
import java.time.Duration;
import java.util.List;
import org.springframework.data.domain.Page;

/** What a clinician records for one patient visit (SRS 3.19). */
public interface ClinicalEncounterService {

    /** How long a completed encounter stays editable before it locks (SRS BR-27). */
    Duration EDIT_WINDOW = Duration.ofHours(24);

    Page<ClinicalEncounter> list(ListParams params);

    List<ClinicalEncounter> getMany(List<Long> ids);

    ClinicalEncounter get(Long id);

    /** Opens an encounter by the signed-in clinician, at their clinic, for a patient that clinic can see. */
    ClinicalEncounter create(EncounterRequest request);

    /** Edits it; refused once it has been completed for longer than {@link #EDIT_WINDOW}. */
    ClinicalEncounter update(Long id, EncounterRequest request);

    /** Closes the visit, which starts the edit window. */
    ClinicalEncounter complete(Long id);

    /** Where the encounter's visit stands and what it went out for. */
    com.vn.vitalcare.care.encounter.dto.VisitState visit(Long id);

    /** The patient leaves the room for the services ordered and comes back when their results are in. */
    com.vn.vitalcare.care.encounter.dto.VisitState sendOut(Long id);

    /** The patient sees another doctor of the clinic today, then comes back. */
    com.vn.vitalcare.care.encounter.dto.VisitState refer(Long id, com.vn.vitalcare.care.encounter.dto.ReferRequest request);
}
