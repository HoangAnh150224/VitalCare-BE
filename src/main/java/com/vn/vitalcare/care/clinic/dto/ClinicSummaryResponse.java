package com.vn.vitalcare.care.clinic.dto;

/** The three figures on a clinic's page (SRS 3.18.3): its active staff, devices and patients. */
public record ClinicSummaryResponse(long staff, long devices, long patients) {
}
