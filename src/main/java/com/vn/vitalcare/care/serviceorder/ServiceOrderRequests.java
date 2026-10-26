package com.vn.vitalcare.care.serviceorder;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** The bodies the service-order endpoints take. */
public final class ServiceOrderRequests {

    private ServiceOrderRequests() {
    }

    /** {@code POST /clinical_encounters/{id}/service_orders}: a doctor orders a service. */
    public record Order(
            @NotNull(message = "Pick a service")
            Long serviceId,

            @Size(max = 1000, message = "The instruction must be at most 1000 characters")
            String instruction) {
    }

    /** {@code PATCH /service_orders/{id}/result}: the result, as text; files go up separately. */
    public record Result(
            @NotBlank(message = "Write the result")
            @Size(max = 20000, message = "The result must be at most 20000 characters")
            String resultText) {
    }

    /** {@code POST /service_orders/{id}/cancel}. */
    public record Cancel(
            @NotBlank(message = "Say why it is cancelled")
            @Size(max = 500, message = "The reason must be at most 500 characters")
            String reason) {
    }
}
