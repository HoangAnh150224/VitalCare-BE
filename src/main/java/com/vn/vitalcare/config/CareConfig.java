package com.vn.vitalcare.config;

import com.vn.vitalcare.care.clinic.ClinicProperties;
import com.vn.vitalcare.care.registration.service.OtpProperties;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Settings and the clock for registration and the appointment book.
 */
@Configuration
@EnableConfigurationProperties({OtpProperties.class, ClinicProperties.class})
public class CareConfig {

    /**
     * The time source for rules that depend on the date: a code expiring, an
     * appointment being today. Injected rather than read with {@code now()} so
     * a test can stand on any day it likes instead of whichever day it runs.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
