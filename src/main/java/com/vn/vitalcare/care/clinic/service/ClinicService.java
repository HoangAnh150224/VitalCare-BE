package com.vn.vitalcare.care.clinic.service;

import com.vn.vitalcare.care.clinic.ClinicProperties;
import com.vn.vitalcare.care.clinic.repository.ClinicRepository;
import com.vn.vitalcare.entity.Clinic;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only until clinic management exists; the seed provides the clinics. */
@Service
@Transactional(readOnly = true)
public class ClinicService {

    private final ClinicRepository repository;
    private final ClinicProperties properties;
    private final Clock clock;

    public ClinicService(ClinicRepository repository, ClinicProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    public List<Clinic> listAll() {
        return repository.findByDeletedAtIsNullOrderByClinicNameAsc();
    }

    public Clinic get(UUID id) {
        return repository.findByClinicIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Clinic", id));
    }

    /**
     * The clinic, locked for the rest of the caller's transaction — what a
     * booking takes before counting a slot. See {@link ClinicRepository#findForUpdate}.
     */
    @Transactional
    public Clinic getForUpdate(UUID id) {
        return repository.findForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Clinic", id));
    }

    public ZoneId timeZone() {
        return properties.timeZone();
    }

    /** Today, as the clinic counts days. */
    public LocalDate today() {
        return LocalDate.now(clock.withZone(properties.timeZone()));
    }

    /** The last day a slot may be booked for. */
    public LocalDate lastBookableDate() {
        return today().plusDays(properties.bookingWindowDays());
    }

    public boolean isWithinBookingWindow(LocalDate date) {
        return !date.isBefore(today()) && !date.isAfter(lastBookableDate());
    }
}
