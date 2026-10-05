package com.vn.vitalcare.care.clinic.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.care.appointment.repository.AppointmentRepository;
import com.vn.vitalcare.care.clinic.ClinicProperties;
import com.vn.vitalcare.care.clinic.repository.ClinicRepository;
import com.vn.vitalcare.care.clinic.repository.ClinicWorkingHoursRepository;
import com.vn.vitalcare.entity.Clinic;
import com.vn.vitalcare.entity.ClinicWorkingHours;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The seeded demo clinic, in memory: Monday to Saturday, 07:30–11:30 and
 * 13:30–16:30, half-hour slots of three. Shared by the schedule and booking
 * tests so both stand on the same week.
 */
public final class ScheduleFixture {

    public static final ZoneId CLINIC_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    public static final UUID CLINIC_ID = UUID.fromString("5b1f8c2e-6a3d-4f7e-9c41-2d8e0a7b3c19");

    public final Clinic clinic = new Clinic();
    public final ClinicRepository clinics = mock(ClinicRepository.class);
    public final ClinicWorkingHoursRepository hours = mock(ClinicWorkingHoursRepository.class);
    public final AppointmentRepository appointments = mock(AppointmentRepository.class);
    public final ClinicService clinicService;
    public final ClinicScheduleService schedule;

    /** Places taken per start time, as countBySlot will report them. */
    public final Map<LocalTime, Long> booked = new HashMap<>();

    public ScheduleFixture(Clock clock) {
        clinic.setClinicId(CLINIC_ID);
        clinic.setClinicName("VitalCare Clinic");
        when(clinics.findByClinicIdAndDeletedAtIsNull(CLINIC_ID)).thenReturn(Optional.of(clinic));
        when(clinics.findForUpdate(CLINIC_ID)).thenReturn(Optional.of(clinic));

        when(hours.findDay(eq(CLINIC_ID), anyShort())).thenAnswer(invocation -> {
            short day = invocation.getArgument(1);
            List<ClinicWorkingHours> sessions = new ArrayList<>();
            if (day <= DayOfWeek.SATURDAY.getValue()) {
                sessions.add(session(DayOfWeek.of(day), "07:30", "11:30"));
                sessions.add(session(DayOfWeek.of(day), "13:30", "16:30"));
            }
            return sessions;
        });
        when(hours.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(appointments.countBySlot(eq(CLINIC_ID), any(), any())).thenAnswer(invocation -> {
            List<AppointmentRepository.SlotCount> rows = new ArrayList<>();
            booked.forEach((start, count) -> rows.add(new AppointmentRepository.SlotCount() {
                @Override
                public LocalTime getStartTime() {
                    return start;
                }

                @Override
                public long getBooked() {
                    return count;
                }
            }));
            return rows;
        });

        ClinicProperties properties = new ClinicProperties(CLINIC_ZONE, 30);
        clinicService = new ClinicService(clinics, properties, clock);
        schedule = new ClinicScheduleService(clinics, hours, appointments, properties, clock);
    }

    private ClinicWorkingHours session(DayOfWeek day, String open, String close) {
        return new ClinicWorkingHours(clinic, day, LocalTime.parse(open), LocalTime.parse(close), 30, 3);
    }
}
