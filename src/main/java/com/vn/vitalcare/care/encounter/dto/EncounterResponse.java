package com.vn.vitalcare.care.encounter.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.vn.vitalcare.care.encounter.service.ClinicalEncounterService;
import com.vn.vitalcare.entity.ClinicalEncounter;
import com.vn.vitalcare.entity.EncounterStatus;
import com.vn.vitalcare.entity.StaffType;
import com.vn.vitalcare.entity.VisitVitals;
import com.vn.vitalcare.identity.rowlevel.service.RowLevelSecurity;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/** A clinical encounter as the API returns it. */
public record EncounterResponse(
        Long id,
        EncounterStatus status,
        PatientSummary patient,
        ClinicianSummary clinician,
        UUID clinicId,
        Long appointmentId,
        OffsetDateTime startedAt,
        OffsetDateTime endedAt,
        /** Until when a completed encounter can still be edited (BR-27); null while in progress. */
        OffsetDateTime editableUntil,
        String chiefComplaint,
        String examinationFindings,
        /** Null when none were measured at the visit. */
        VisitVitals vitals,
        String clinicalNote,
        String diagnosis,
        String followUp,
        /** What the caller may do to this row; see {@code DeviceResponse}. */
        @JsonProperty("_can")
        Map<String, Boolean> can) {

    public record PatientSummary(Long id, String customerCode, String fullName) {
    }

    public record ClinicianSummary(Long id, String fullName, StaffType staffType) {
    }

    public static EncounterResponse from(ClinicalEncounter encounter, RowLevelSecurity rowLevel) {
        var customer = encounter.getCustomer();
        var employee = encounter.getEmployee();
        return new EncounterResponse(
                encounter.getId(),
                encounter.getStatus(),
                new PatientSummary(customer.getId(), customer.getCustomerCode(), customer.getFullName()),
                employee == null ? null
                        : new ClinicianSummary(employee.getId(), employee.getUser().getFullName(), employee.getStaffType()),
                encounter.getClinicId(),
                // The id only: it reads off the proxy without loading the appointment.
                encounter.getAppointment() == null ? null : encounter.getAppointment().getId(),
                encounter.getStartedAt(),
                encounter.getEndedAt(),
                encounter.getEndedAt() == null ? null : encounter.getEndedAt().plus(ClinicalEncounterService.EDIT_WINDOW),
                encounter.getChiefComplaint(),
                encounter.getExaminationFindings(),
                encounter.getVitals(),
                encounter.getClinicalNote(),
                encounter.getDiagnosis(),
                encounter.getFollowUp(),
                rowLevel == null ? Map.of() : rowLevel.capabilities(encounter));
    }
}
