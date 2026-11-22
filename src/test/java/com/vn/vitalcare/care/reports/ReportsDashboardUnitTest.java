package com.vn.vitalcare.care.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.identity.rowlevel.service.RowLevelPrincipalResolver;
import com.vn.vitalcare.share.exception.ConflictException;
import com.vn.vitalcare.share.security.rowlevel.RowLevelPrincipal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/** Report-5.1 BE_Gaps2: Reports.dashboard and Reports.report summaries (BF-10 Monitoring Dashboard, FE-12 reports). */
class ReportsDashboardUnitTest {

    private static final UUID MINE = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-22T02:00:00Z"), ZoneOffset.UTC);

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final RowLevelPrincipalResolver principals = mock(RowLevelPrincipalResolver.class);
    private final Reports reports = new Reports(jdbc, principals, CLOCK);

    private void as(UUID clinicId, String role) {
        when(principals.current()).thenReturn(Optional.of(new RowLevelPrincipal(1L, null, null, clinicId, Set.of(role), Set.of())));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }

    @BeforeEach
    void answers() {
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Long.class))).thenReturn(4L);
        when(jdbc.queryForMap(anyString(), any(SqlParameterSource.class))).thenReturn(Map.of("open_critical", 1, "low_battery", 2));
        when(jdbc.queryForList(anyString(), any(SqlParameterSource.class))).thenReturn(List.of());
    }

    @Test
    @DisplayName("The dashboard has every section, in camelCase, for the caller's own clinic")
    void rp01_dashboardSections() {
        as(MINE, "CLINIC_ADMIN");
        Map<String, Object> body = reports.dashboard(UUID.randomUUID());
        assertThat(body).containsKeys("clinicId", "generatedAt", "monitoredPatients", "devices", "alerts",
                "appointmentsToday", "sync", "intake", "alertsByDay", "recentCritical");
        assertThat(body.get("clinicId")).isEqualTo(MINE);
        assertThat(body.get("monitoredPatients")).isEqualTo(4L);
        assertThat(map(body.get("alerts"))).containsEntry("openCritical", 1);
        assertThat(map(body.get("devices"))).containsEntry("lowBattery", 2);
    }

    @Test
    @DisplayName("A clinic's figures are filtered by clinic_id = :clinicId; the administrator's all-clinics view has no filter")
    void rp02_clinicFilter() {
        as(MINE, "DOCTOR");
        reports.dashboard(null);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc, atLeastOnce()).queryForMap(sql.capture(), params.capture());
        assertThat(sql.getAllValues()).allMatch(s -> s.contains(".clinic_id = :clinicId"));
        assertThat(((MapSqlParameterSource) params.getValue()).getValue("clinicId")).isEqualTo(MINE);

        org.mockito.Mockito.clearInvocations(jdbc);
        as(null, "ADMIN");
        assertThat(reports.dashboard(null).get("clinicId")).isNull();
        ArgumentCaptor<String> all = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeastOnce()).queryForMap(all.capture(), any(SqlParameterSource.class));
        assertThat(all.getAllValues()).noneMatch(s -> s.contains(":clinicId"));
    }

    @Test
    @DisplayName("Staff tied to no clinic and not administrator: refused before any query")
    void rp03_noClinicRefused() {
        as(null, "DOCTOR");
        assertThatThrownBy(() -> reports.dashboard(null)).isInstanceOf(ConflictException.class);
        when(principals.current()).thenReturn(Optional.empty());
        assertThatThrownBy(() -> reports.dashboard(null)).isInstanceOf(ConflictException.class);
        verifyNoInteractions(jdbc);
    }

    @Test
    @DisplayName("Devices report: summary counts assigned, online, low battery (< 20%), readings and sync failures from the rows")
    void rp04_devicesSummary() {
        as(MINE, "CLINIC_ADMIN");
        when(jdbc.queryForList(anyString(), any(SqlParameterSource.class))).thenReturn(List.of(
                Map.of("status", "ASSIGNED", "connectivity", "ONLINE", "battery_level", 19, "readings", 10, "sync_failures", 1),
                Map.of("status", "AVAILABLE", "connectivity", "OFFLINE", "battery_level", 20, "readings", 5, "sync_failures", 0)));
        Map<String, Object> body = reports.report(Reports.Kind.devices, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 22), null);
        assertThat(map(body.get("summary"))).containsEntry("devices", 2).containsEntry("assigned", 1L)
                .containsEntry("online", 1L).containsEntry("lowBattery", 1L).containsEntry("readings", 15L)
                .containsEntry("syncFailures", 1L);
        assertThat(body).containsEntry("kind", "devices");
    }

    @Test
    @DisplayName("Patients report: patients and visits are summed from the rows; new patients come from their own count")
    void rp05_patientsSummary() {
        as(MINE, "CLINIC_ADMIN");
        when(jdbc.queryForList(anyString(), any(SqlParameterSource.class))).thenReturn(List.of(
                Map.of("monitored", true, "visits", 2), Map.of("monitored", false, "visits", 1)));
        Map<String, Object> body = reports.report(Reports.Kind.patients, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 22), null);
        assertThat(map(body.get("summary"))).containsEntry("patients", 2).containsEntry("newPatients", 4L)
                .containsEntry("monitored", 1L).containsEntry("visits", 3L);
    }

    @Test
    @DisplayName("Boundary: a period of exactly one day is accepted")
    void rp06_oneDayPeriod() {
        as(MINE, "CLINIC_ADMIN");
        LocalDate d = LocalDate.of(2026, 11, 22);
        assertThat(reports.report(Reports.Kind.alerts, d, d, null)).containsEntry("from", d).containsEntry("to", d);
    }
}
