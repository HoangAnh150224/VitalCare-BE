package com.vn.vitalcare.care.appointment.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * J-01: a visit still booked after its day's last turn is a no-show, and its
 * place goes back to the day (BR-10). Its unpaid bill is cancelled with it;
 * the account's no-shows are what BR-11 counts.
 */
@Component
public class NoShowSweep {

    private static final Logger log = LoggerFactory.getLogger(NoShowSweep.class);

    private final AppointmentService appointments;

    public NoShowSweep(AppointmentService appointments) {
        this.appointments = appointments;
    }

    @Scheduled(fixedDelayString = "${app.no-show.sweep:15m}", initialDelayString = "${app.no-show.sweep:15m}")
    public void sweep() {
        int marked = appointments.markNoShowsDue();
        if (marked > 0) {
            log.info("Marked {} booked visit(s) as no-shows", marked);
        }
    }
}
