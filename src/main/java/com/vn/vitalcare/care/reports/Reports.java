package com.vn.vitalcare.care.reports;

import com.vn.vitalcare.identity.rowlevel.service.RowLevelPrincipalResolver;
import com.vn.vitalcare.share.exception.ConflictException;
import com.vn.vitalcare.share.security.rowlevel.RowLevelPrincipal;
import com.vn.vitalcare.share.exception.FieldValidationException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * BF-10 Clinic Monitoring Dashboard and BF-08's summary reports, counted in
 * the database: a clinic's member of staff sees their clinic; the system
 * administrator, tied to none, sees every clinic or the one they pick. Anybody
 * else tied to none is refused, as the revenue report refuses them.
 *
 * <p>A patient belongs to a clinic once they have a visit there or wear one
 * of its devices, as in {@code CustomerSpecifications.visibleAtClinic}.
 */
@Service
@Transactional(readOnly = true)
public class Reports {

    static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    public static final int LOW_BATTERY = 20;

    private final NamedParameterJdbcTemplate jdbc;
    private final RowLevelPrincipalResolver principals;
    private final Clock clock;

    public Reports(NamedParameterJdbcTemplate jdbc, RowLevelPrincipalResolver principals, Clock clock) {
        this.jdbc = jdbc;
        this.principals = principals;
        this.clock = clock;
    }

    /** The caller's clinic; for somebody tied to none, the one asked for, or null for all. */
    UUID clinic(UUID asked) {
        RowLevelPrincipal me = principals.current().orElse(null);
        if (me != null && me.clinicId() != null) {
            return me.clinicId();
        }
        if (me != null && me.roleCodes().contains("ADMIN")) {
            return asked;
        }
        throw new ConflictException("These figures are a clinic's; your account has no clinic");
    }

    // ---------------------------------------------------------------- dashboard

    public Map<String, Object> dashboard(UUID asked) {
        UUID clinicId = clinic(asked);
        OffsetDateTime now = OffsetDateTime.now(clock);
        LocalDate today = now.atZoneSameInstant(ZONE).toLocalDate();
        MapSqlParameterSource p = params(clinicId)
                .addValue("today", today)
                .addValue("dayStart", ts(today.atStartOfDay(ZONE).toOffsetDateTime()))
                .addValue("hourAgo", ts(now.minusHours(1)))
                .addValue("dayAgo", ts(now.minusDays(1)))
                .addValue("weekStart", ts(today.minusDays(6).atStartOfDay(ZONE).toOffsetDateTime()))
                .addValue("low", LOW_BATTERY);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clinicId", clinicId);
        body.put("generatedAt", now);
        body.put("monitoredPatients", jdbc.queryForObject("""
                select count(distinct da.customer_id) from device_assignment da
                join medical_device d on d.id = da.device_id
                where da.status = 'ACTIVE' and da.deleted_at is null""" + and("d", clinicId), p, Long.class));
        body.put("devices", jdbc.queryForMap("""
                select count(*) as total,
                       count(*) filter (where connectivity = 'ONLINE') as online,
                       count(*) filter (where connectivity is distinct from 'ONLINE') as offline,
                       count(*) filter (where status = 'ASSIGNED') as assigned,
                       count(*) filter (where status = 'AVAILABLE') as available,
                       count(*) filter (where battery_level is not null and battery_level < :low) as low_battery
                from medical_device d where d.deleted_at is null""" + and("d", clinicId), p));
        body.put("alerts", jdbc.queryForMap("""
                select count(*) filter (where status = 'OPEN' and severity = 'CRITICAL') as open_critical,
                       count(*) filter (where status = 'OPEN' and severity = 'WARNING') as open_warning,
                       count(*) filter (where status = 'ACKNOWLEDGED') as acknowledged,
                       count(*) filter (where triggered_at >= :dayStart) as raised_today
                from alert a where a.deleted_at is null""" + and("a", clinicId), p));
        body.put("appointmentsToday", jdbc.queryForMap("""
                select count(*) as total,
                       count(*) filter (where status = 'SCHEDULED') as scheduled,
                       count(*) filter (where status = 'CHECKED_IN' and paid_at is null) as awaiting_payment,
                       count(*) filter (where status = 'CHECKED_IN' and paid_at is not null) as waiting,
                       count(*) filter (where status = 'IN_EXAM') as in_exam,
                       count(*) filter (where status = 'AWAITING_RESULTS') as awaiting_results,
                       count(*) filter (where status = 'COMPLETED') as completed,
                       count(*) filter (where status in ('CANCELLED', 'NO_SHOW')) as cancelled
                from appointment a where a.deleted_at is null and a.appointment_date = :today""" + and("a", clinicId), p));
        body.put("sync", jdbc.queryForMap("""
                select (select count(*) from sync_failure f where f.status = 'OPEN' and f.deleted_at is null""" + and("f", clinicId) + """
                       ) as open_failures,
                       count(*) filter (where b.received_at >= :dayStart) as batches_today,
                       coalesce(sum(b.rejected_count) filter (where b.received_at >= :dayStart), 0) as rejected_today,
                       max(b.received_at) as last_sync_at
                from sync_batch b where b.deleted_at is null""" + and("b", clinicId), p));
        body.put("intake", jdbc.queryForMap("""
                select count(*) filter (where v.received_at >= :hourAgo) as last_hour,
                       count(*) filter (where v.received_at >= :dayAgo) as last_day,
                       count(*) filter (where v.received_at >= :dayAgo and v.validation_status is distinct from 'VALID') as invalid_last_day,
                       max(v.measured_at) as last_reading_at
                from vital_signs v join medical_device d on d.id = v.device_id
                where v.deleted_at is null and v.received_at >= :weekStart""" + and("d", clinicId), p));
        body.put("alertsByDay", jdbc.queryForList("""
                select to_char(g.day, 'YYYY-MM-DD') as date,
                       count(a.id) filter (where a.severity = 'CRITICAL') as critical,
                       count(a.id) filter (where a.severity = 'WARNING') as warning
                from generate_series(cast(:weekStart as timestamptz) at time zone 'Asia/Ho_Chi_Minh',
                                     cast(:dayStart as timestamptz) at time zone 'Asia/Ho_Chi_Minh', interval '1 day') g(day)
                left join alert a on a.deleted_at is null and a.triggered_at >= :weekStart
                     and (a.triggered_at at time zone 'Asia/Ho_Chi_Minh')::date = g.day::date""" + and("a", clinicId) + """
                group by g.day order by g.day""", p));
        body.put("recentCritical", jdbc.queryForList("""
                select a.id, a.customer_id, coalesce(c.full_name, u.full_name) as customer_name, a.metric, a.measured_value, a.status, a.triggered_at
                from alert a join customer c on c.id = a.customer_id left join users u on u.id = c.user_id
                where a.deleted_at is null and a.severity = 'CRITICAL' and a.status in ('OPEN', 'ACKNOWLEDGED')""" + and("a", clinicId) + """
                order by a.triggered_at desc limit 5""", p));
        return camel(body);
    }

    // ---------------------------------------------------------------- reports

    public enum Kind { patients, devices, vitals, alerts }

    public Map<String, Object> report(Kind kind, LocalDate from, LocalDate to, UUID asked) {
        if (from == null || to == null) {
            throw new FieldValidationException(from == null ? "from" : "to", "Choose the period");
        }
        if (to.isBefore(from)) {
            throw new FieldValidationException("to", "The end date is before the start");
        }
        if (from.plusDays(366).isBefore(to)) {
            throw new FieldValidationException("to", "A report covers at most a year");
        }
        UUID clinicId = clinic(asked);
        MapSqlParameterSource p = params(clinicId)
                .addValue("from", ts(from.atStartOfDay(ZONE).toOffsetDateTime()))
                .addValue("to", ts(to.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime()))
                .addValue("fromDay", from)
                .addValue("toDay", to);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", kind.name());
        body.put("clinicId", clinicId);
        body.put("from", from);
        body.put("to", to);
        switch (kind) {
            case patients -> patients(body, p, clinicId);
            case devices -> devices(body, p, clinicId);
            case vitals -> vitals(body, p, clinicId);
            case alerts -> alerts(body, p, clinicId);
        }
        return camel(body);
    }

    /** Patients seen (a visit) or monitored (a device worn) in the period. */
    private void patients(Map<String, Object> body, MapSqlParameterSource p, UUID clinicId) {
        String seen = """
                with seen as (
                  select a.customer_id from appointment a
                  where a.deleted_at is null and a.appointment_date between :fromDay and :toDay""" + and("a", clinicId) + """
                  union
                  select da.customer_id from device_assignment da join medical_device d on d.id = da.device_id
                  where da.deleted_at is null and da.assigned_at < :to and (da.unassigned_at is null or da.unassigned_at >= :from)""" + and("d", clinicId) + """
                )
                """;
        List<Map<String, Object>> rows = jdbc.queryForList(seen + """
                select c.customer_code, coalesce(c.full_name, u.full_name) as full_name, c.gender, c.date_of_birth,
                       (select count(*) from appointment a where a.customer_id = c.id and a.deleted_at is null
                          and a.status = 'COMPLETED' and a.appointment_date between :fromDay and :toDay""" + and("a", clinicId) + """
                       ) as visits,
                       (select max(a.appointment_date) from appointment a where a.customer_id = c.id and a.deleted_at is null
                          and a.status = 'COMPLETED'""" + and("a", clinicId) + """
                       ) as last_visit,
                       exists (select 1 from device_assignment da join medical_device d on d.id = da.device_id
                          where da.customer_id = c.id and da.status = 'ACTIVE' and da.deleted_at is null""" + and("d", clinicId) + """
                       ) as monitored,
                       (select count(*) from vital_signs v join medical_device d on d.id = v.device_id where v.customer_id = c.id
                          and v.deleted_at is null and v.measured_at >= :from and v.measured_at < :to""" + and("d", clinicId) + """
                       ) as readings,
                       (select count(*) from alert a where a.customer_id = c.id and a.deleted_at is null
                          and a.triggered_at >= :from and a.triggered_at < :to""" + and("a", clinicId) + """
                       ) as alerts,
                       (select count(*) from alert a where a.customer_id = c.id and a.deleted_at is null and a.severity = 'CRITICAL'
                          and a.triggered_at >= :from and a.triggered_at < :to""" + and("a", clinicId) + """
                       ) as critical_alerts
                from customer c join seen s on s.customer_id = c.id left join users u on u.id = c.user_id
                where c.deleted_at is null
                order by 2""", p);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("patients", rows.size());
        summary.put("newPatients", jdbc.queryForObject(seen + """
                select count(*) from customer c join seen s on s.customer_id = c.id
                where c.deleted_at is null and c.patient_activated_at >= :from and c.patient_activated_at < :to""", p, Long.class));
        summary.put("monitored", rows.stream().filter(r -> Boolean.TRUE.equals(r.get("monitored"))).count());
        summary.put("visits", rows.stream().mapToLong(r -> ((Number) r.get("visits")).longValue()).sum());
        body.put("summary", summary);
        body.put("rows", rows);
    }

    private void devices(Map<String, Object> body, MapSqlParameterSource p, UUID clinicId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select d.device_code, d.model, d.device_type, d.status, d.connectivity, d.battery_level, d.firmware_version,
                       d.last_sync_at,
                       (select coalesce(c.full_name, u.full_name) from device_assignment da join customer c on c.id = da.customer_id
                          left join users u on u.id = c.user_id
                          where da.device_id = d.id and da.status = 'ACTIVE' and da.deleted_at is null limit 1) as worn_by,
                       (select count(*) from vital_signs v where v.device_id = d.id and v.deleted_at is null
                          and v.measured_at >= :from and v.measured_at < :to) as readings,
                       (select count(*) from sync_batch b where b.device_id = d.id and b.deleted_at is null
                          and b.received_at >= :from and b.received_at < :to) as syncs,
                       (select count(*) from sync_failure f where f.device_id = d.id and f.deleted_at is null
                          and f.created_at >= :from and f.created_at < :to) as sync_failures
                from medical_device d where d.deleted_at is null""" + and("d", clinicId) + """
                order by d.device_code""", p);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("devices", rows.size());
        summary.put("assigned", rows.stream().filter(r -> "ASSIGNED".equals(r.get("status"))).count());
        summary.put("online", rows.stream().filter(r -> "ONLINE".equals(r.get("connectivity"))).count());
        summary.put("lowBattery", rows.stream().filter(r -> r.get("battery_level") instanceof Number n && n.intValue() < LOW_BATTERY).count());
        summary.put("readings", rows.stream().mapToLong(r -> ((Number) r.get("readings")).longValue()).sum());
        summary.put("syncFailures", rows.stream().mapToLong(r -> ((Number) r.get("sync_failures")).longValue()).sum());
        body.put("summary", summary);
        body.put("rows", rows);
    }

    /** Per patient: how many readings, and the range of each measure among the valid ones. */
    private void vitals(Map<String, Object> body, MapSqlParameterSource p, UUID clinicId) {
        String from = """
                from vital_signs v join medical_device d on d.id = v.device_id
                where v.deleted_at is null and v.customer_id is not null
                  and v.measured_at >= :from and v.measured_at < :to""" + and("d", clinicId);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select c.customer_code, coalesce(c.full_name, u.full_name) as full_name,
                       count(*) as readings,
                       count(*) filter (where v.validation_status is distinct from 'VALID') as invalid,
                       round(avg(v.heart_rate) filter (where v.validation_status = 'VALID'), 0) as avg_heart_rate,
                       min(v.heart_rate) filter (where v.validation_status = 'VALID') as min_heart_rate,
                       max(v.heart_rate) filter (where v.validation_status = 'VALID') as max_heart_rate,
                       round(avg(v.spo2) filter (where v.validation_status = 'VALID'), 1) as avg_spo2,
                       min(v.spo2) filter (where v.validation_status = 'VALID') as min_spo2,
                       max(v.temperature) filter (where v.validation_status = 'VALID') as max_temperature,
                       max(v.systolic_bp) filter (where v.validation_status = 'VALID') as max_systolic_bp,
                       min(v.measured_at) as first_at, max(v.measured_at) as last_at
                """ + from.replace("from vital_signs v", "from vital_signs v join customer c on c.id = v.customer_id left join users u on u.id = c.user_id") + """
                group by c.id, c.customer_code, c.full_name, u.full_name order by 2""", p);
        Map<String, Object> summary = new LinkedHashMap<>(jdbc.queryForMap("""
                select count(*) as readings,
                       count(*) filter (where v.validation_status is distinct from 'VALID') as invalid,
                       count(distinct v.customer_id) as patients,
                       count(distinct v.device_id) as devices
                """ + from, p));
        body.put("summary", summary);
        body.put("rows", rows);
    }

    /** Per day: raised by severity, how they ended, and how long they took to be picked up. */
    private void alerts(Map<String, Object> body, MapSqlParameterSource p, UUID clinicId) {
        String from = """
                from alert a where a.deleted_at is null and a.triggered_at >= :from and a.triggered_at < :to""" + and("a", clinicId);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select to_char((a.triggered_at at time zone 'Asia/Ho_Chi_Minh')::date, 'YYYY-MM-DD') as date,
                       count(*) as alerts,
                       count(*) filter (where a.severity = 'CRITICAL') as critical,
                       count(*) filter (where a.severity = 'WARNING') as warning,
                       count(*) filter (where a.status = 'RESOLVED') as resolved,
                       count(*) filter (where a.status = 'DISMISSED') as dismissed,
                       count(*) filter (where a.status in ('OPEN', 'ACKNOWLEDGED')) as still_open,
                       round(avg(extract(epoch from (a.acknowledged_at - a.triggered_at)) / 60.0)
                             filter (where a.acknowledged_at is not null), 1) as avg_minutes_to_acknowledge
                """ + from + """
                group by 1 order by 1""", p);
        Map<String, Object> summary = new LinkedHashMap<>(jdbc.queryForMap("""
                select count(*) as alerts,
                       count(*) filter (where a.severity = 'CRITICAL') as critical,
                       count(*) filter (where a.severity = 'WARNING') as warning,
                       count(*) filter (where a.status = 'RESOLVED') as resolved,
                       count(*) filter (where a.status = 'DISMISSED') as dismissed,
                       count(*) filter (where a.status in ('OPEN', 'ACKNOWLEDGED')) as still_open,
                       count(distinct a.customer_id) as patients,
                       round(avg(extract(epoch from (a.acknowledged_at - a.triggered_at)) / 60.0)
                             filter (where a.acknowledged_at is not null), 1) as avg_minutes_to_acknowledge
                """ + from, p));
        summary.put("byMetric", jdbc.queryForList("select a.metric, count(*) as alerts " + from
                + " group by a.metric order by count(*) desc", p));
        body.put("summary", summary);
        body.put("rows", rows);
    }

    // ---------------------------------------------------------------- helpers

    private static String and(String alias, UUID clinicId) {
        return clinicId == null ? "\n" : "\n and " + alias + ".clinic_id = :clinicId\n";
    }

    private static MapSqlParameterSource params(UUID clinicId) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        if (clinicId != null) {
            p.addValue("clinicId", clinicId);
        }
        return p;
    }

    private static Timestamp ts(OffsetDateTime at) {
        return Timestamp.from(at.toInstant());
    }

    /** snake_case columns to the camelCase the rest of the API speaks, all the way down. */
    @SuppressWarnings("unchecked")
    static <T> T camel(T value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(camelKey(String.valueOf(k)), camel(v)));
            return (T) out;
        }
        if (value instanceof List<?> list) {
            return (T) list.stream().map(Reports::camel).toList();
        }
        if (value instanceof Timestamp t) {
            return (T) t.toInstant();
        }
        if (value instanceof java.sql.Date d) {
            return (T) d.toLocalDate();
        }
        return value;
    }

    static String camelKey(String key) {
        StringBuilder out = new StringBuilder();
        boolean up = false;
        for (char ch : key.toCharArray()) {
            if (ch == '_') {
                up = true;
            } else {
                out.append(up ? Character.toUpperCase(ch) : ch);
                up = false;
            }
        }
        return out.toString();
    }
}
