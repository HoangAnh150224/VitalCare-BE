package com.vn.vitalcare.care.clinic.service;

import com.vn.vitalcare.care.clinic.dto.ClinicRequest;
import com.vn.vitalcare.care.clinic.repository.ClinicRepository;
import com.vn.vitalcare.entity.Clinic;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

public interface ClinicService {

    List<Clinic> listAll();

    Clinic get(UUID id);

    /** A new clinic, open, with no working hours yet: nothing can be booked there until they are set. */
    Clinic create(ClinicRequest request);

    Clinic update(UUID id, ClinicRequest request);

    /**
     * The clinic, locked for the rest of the caller's transaction — what a
     * booking takes before counting a slot. See {@link ClinicRepository#findForUpdate}.
     */
    Clinic getForUpdate(UUID id);

    ZoneId timeZone();

    /** Today, as the clinic counts days. */
    LocalDate today();

    /** The last day a slot may be booked for. */
    LocalDate lastBookableDate();

    boolean isWithinBookingWindow(LocalDate date);
}
