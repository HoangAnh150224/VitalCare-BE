package com.vn.vitalcare.care.encounter.dto;

import com.vn.vitalcare.care.serviceorder.ServiceOrderResponse;
import java.util.List;

/**
 * {@code GET /my_appointments/{id}/result}: what the doctor found and said, and
 * the services done in the visit with their results. The staff shapes, with
 * no capabilities ({@code _can} is empty: the customer can change none of it).
 */
public record MyVisitResult(EncounterResponse encounter, List<ServiceOrderResponse> orders) {
}
