package com.vn.vitalcare;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AppBeApplication {

    public static void main(String[] args) {
        // The JVM runs in UTC, whatever the machine is set to. Hibernate moves
        // LocalTime and LocalDate through java.sql.Time/Date, which are pinned
        // to 1970-01-01 in the default zone, and Ho Chi Minh City was UTC+8 in
        // 1970: on a Vietnamese machine every `time` column read back eight
        // hours off, and only rows the application also wrote looked right,
        // because the shift cancelled out. Wall-clock values (opening hours,
        // appointment times) must round-trip exactly; in UTC they do.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(AppBeApplication.class, args);
    }

}
