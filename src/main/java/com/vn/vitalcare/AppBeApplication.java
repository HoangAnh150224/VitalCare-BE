package com.vn.vitalcare;

import com.vn.vitalcare.share.data.SecuredRepositoryImpl;
import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * The one explicit piece, as in the framework, is {@link EnableJpaRepositories},
 * here only to name a base class: {@link SecuredRepositoryImpl} lends
 * {@code save}, {@code delete} and {@code flush} back to
 * {@link com.vn.vitalcare.share.data.SecuredRepository}, which deliberately
 * inherits nothing. Registering it for every repository is harmless: Spring
 * Data exposes only what a repository's own interface declares. Declaring
 * {@code basePackages} is required once the annotation is present at all,
 * because it replaces the default scan Boot would otherwise configure.
 */
@SpringBootApplication
@EnableJpaRepositories(
        basePackages = "com.vn.vitalcare",
        repositoryBaseClass = SecuredRepositoryImpl.class)
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
