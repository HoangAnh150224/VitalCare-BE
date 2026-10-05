package com.vn.vitalcare.entity;

import com.vn.vitalcare.share.data.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.DayOfWeek;
import java.time.LocalTime;

/**
 * One session of one weekday at one clinic — "Mondays 07:30–11:30, half-hour
 * slots, three people each". A weekday without a row is a closed day.
 *
 * <p>Slots are cut from these rows on demand rather than stored; see
 * {@code ClinicScheduleService}.
 */
@Entity
@Table(name = "clinic_working_hours")
public class ClinicWorkingHours extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "clinic_id", nullable = false)
    private Clinic clinic;

    /** ISO numbering, as {@link DayOfWeek#getValue()}: 1 = Monday … 7 = Sunday. */
    @Column(name = "day_of_week", nullable = false)
    private short dayOfWeek;

    @Column(name = "open_time", nullable = false)
    private LocalTime openTime;

    @Column(name = "close_time", nullable = false)
    private LocalTime closeTime;

    @Column(name = "slot_minutes", nullable = false)
    private int slotMinutes;

    @Column(name = "capacity_per_slot", nullable = false)
    private int capacityPerSlot;

    public ClinicWorkingHours() {
    }

    public ClinicWorkingHours(Clinic clinic, DayOfWeek day, LocalTime openTime, LocalTime closeTime,
                              int slotMinutes, int capacityPerSlot) {
        this.clinic = clinic;
        this.dayOfWeek = (short) day.getValue();
        this.openTime = openTime;
        this.closeTime = closeTime;
        this.slotMinutes = slotMinutes;
        this.capacityPerSlot = capacityPerSlot;
    }

    public Long getId() {
        return id;
    }

    public Clinic getClinic() {
        return clinic;
    }

    public DayOfWeek getDayOfWeek() {
        return DayOfWeek.of(dayOfWeek);
    }

    public LocalTime getOpenTime() {
        return openTime;
    }

    public LocalTime getCloseTime() {
        return closeTime;
    }

    public int getSlotMinutes() {
        return slotMinutes;
    }

    public int getCapacityPerSlot() {
        return capacityPerSlot;
    }
}
