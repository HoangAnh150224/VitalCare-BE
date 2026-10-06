package com.vn.vitalcare.care.clinic.service.impl;

import com.vn.vitalcare.care.clinic.ClinicProperties;
import com.vn.vitalcare.care.clinic.repository.ClinicRepository;
import com.vn.vitalcare.care.clinic.service.ClinicService;
import com.vn.vitalcare.entity.Clinic;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The {@link ClinicService} the application runs on. */
@Service
@Transactional(readOnly = true)
public class ClinicServiceImpl implements ClinicService {

    private final ClinicRepository repository;
    private final ClinicProperties properties;
    private final Clock clock;

    public ClinicServiceImpl(ClinicRepository repository, ClinicProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public List<Clinic> listAll() {
        return repository.findByDeletedAtIsNullOrderByClinicNameAsc();
    }

    @Override
    public Clinic get(UUID id) {
        return repository.findByClinicIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Clinic", id));
    }

    @Override
    @Transactional
    public Clinic getForUpdate(UUID id) {
        return repository.findForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Clinic", id));
    }

    @Override
    public ZoneId timeZone() {
        return properties.timeZone();
    }

    @Override
    public LocalDate today() {
        return LocalDate.now(clock.withZone(properties.timeZone()));
    }

    @Override
    public LocalDate lastBookableDate() {
        return today().plusDays(properties.bookingWindowDays());
    }

    @Override
    public boolean isWithinBookingWindow(LocalDate date) {
        return !date.isBefore(today()) && !date.isAfter(lastBookableDate());
    }
}
