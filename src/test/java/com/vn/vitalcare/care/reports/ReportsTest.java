package com.vn.vitalcare.care.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.identity.rowlevel.service.RowLevelPrincipalResolver;
import com.vn.vitalcare.share.exception.ConflictException;
import com.vn.vitalcare.share.security.rowlevel.RowLevelPrincipal;
import java.util.Set;
import com.vn.vitalcare.share.exception.FieldValidationException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** BF-08 / BF-10: whose figures, over which period, and how they read. */
class ReportsTest {

    private final RowLevelPrincipalResolver principals = mock(RowLevelPrincipalResolver.class);
    private final Reports reports = new Reports(mock(NamedParameterJdbcTemplate.class), principals, Clock.systemUTC());

    private void as(UUID clinicId, String role) {
        when(principals.current()).thenReturn(Optional.of(new RowLevelPrincipal(1L, null, null, clinicId, Set.of(role), Set.of())));
    }

    @Test
    @DisplayName("Staff get their clinic whatever they ask; the administrator what they ask; anybody else with none is refused")
    void clinic() {
        UUID mine = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        as(mine, "CLINIC_ADMIN");
        assertThat(reports.clinic(other)).isEqualTo(mine);
        as(null, "ADMIN");
        assertThat(reports.clinic(other)).isEqualTo(other);
        assertThat(reports.clinic(null)).isNull();
        as(null, "DOCTOR");
        assertThatThrownBy(() -> reports.clinic(other)).isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("A period ends after it starts and covers at most a year")
    void period() {
        as(null, "ADMIN");
        LocalDate d = LocalDate.of(2026, 11, 3);
        assertThatThrownBy(() -> reports.report(Reports.Kind.alerts, d, d.minusDays(1), null))
                .isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> reports.report(Reports.Kind.alerts, d.minusDays(400), d, null))
                .isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> reports.report(Reports.Kind.alerts, null, d, null))
                .isInstanceOf(FieldValidationException.class);
    }

    @Test
    @DisplayName("Columns come back camelCase, timestamps as instants, all the way down")
    void camel() {
        Instant at = Instant.parse("2026-11-03T02:00:00Z");
        Map<String, Object> out = Reports.camel(Map.of("open_critical", 2,
                "recent_critical", List.of(Map.of("triggered_at", Timestamp.from(at)))));
        assertThat(out).containsEntry("openCritical", 2);
        assertThat(((List<?>) out.get("recentCritical")).getFirst()).isEqualTo(Map.of("triggeredAt", at));
    }
}
