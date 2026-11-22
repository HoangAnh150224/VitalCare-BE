package com.vn.vitalcare.care.publicsite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.care.billing.catalog.MedicalServiceCatalog;
import com.vn.vitalcare.care.clinic.service.ClinicService;
import com.vn.vitalcare.care.firstaid.FirstAidArticles;
import com.vn.vitalcare.care.firstaid.FirstAidResponses;
import com.vn.vitalcare.care.schedule.service.DoctorAvailabilityService;
import com.vn.vitalcare.care.staff.service.EmployeeService;
import com.vn.vitalcare.entity.Clinic;
import com.vn.vitalcare.entity.DoctorWorkingHours;
import com.vn.vitalcare.entity.Employee;
import com.vn.vitalcare.entity.FirstAidArticleHistory;
import com.vn.vitalcare.entity.FirstAidCategory;
import com.vn.vitalcare.entity.MedicalService;
import com.vn.vitalcare.entity.ServiceCategory;
import com.vn.vitalcare.entity.ServiceStatus;
import com.vn.vitalcare.identity.user.entity.User;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Time;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** UC View Clinic Information, View Services, Emergency (clinic lines) and the first-aid endpoints. */
class GuestPublicSiteUnitTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private ClinicService clinics;
    private EmployeeService employees;
    private DoctorAvailabilityService availability;
    private MedicalServiceCatalog catalog;
    private NamedParameterJdbcTemplate jdbc;
    private PublicSite site;
    /** clinic_working_hours rows: clinic, ISO day, open, close. */
    private final List<Object[]> hourRows = new ArrayList<>();
    private final List<String> sqlSeen = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        clinics = mock(ClinicService.class);
        employees = mock(EmployeeService.class);
        availability = mock(DoctorAvailabilityService.class);
        catalog = mock(MedicalServiceCatalog.class);
        jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.query(anyString(), anyMap(), any(RowMapper.class))).thenAnswer(call -> {
            String sql = call.getArgument(0);
            Map<String, Object> params = call.getArgument(1);
            sqlSeen.add(sql);
            RowMapper<Object> mapper = call.getArgument(2);
            List<Object> out = new ArrayList<>();
            int i = 0;
            for (Object[] row : hourRows) {
                if (params.containsKey("clinicId") && !params.get("clinicId").equals(row[0])) {
                    continue;
                }
                ResultSet rs = mock(ResultSet.class);
                when(rs.getObject("clinic_id", UUID.class)).thenReturn((UUID) row[0]);
                when(rs.getInt("day_of_week")).thenReturn((Integer) row[1]);
                when(rs.getTime("open_time")).thenReturn(Time.valueOf((String) row[2]));
                when(rs.getTime("close_time")).thenReturn(Time.valueOf((String) row[3]));
                out.add(mapper.mapRow(rs, i++));
            }
            return out;
        });
        site = new PublicSite(clinics, employees, availability, catalog, jdbc);
    }

    private static Clinic clinic(UUID id, String name, String status, String phone) {
        Clinic clinic = new Clinic();
        clinic.setClinicId(id);
        clinic.setClinicName(name);
        clinic.setAddress("1 Lê Lợi, Q1");
        clinic.setContactPhone(phone);
        clinic.setContactEmail(phone == null ? null : "a@vitalcare.vn");
        clinic.setOperatingStatus(status);
        return clinic;
    }

    @Nested
    @DisplayName("PublicSite.clinics()")
    class Clinics {

        @Test void utc01_onlyOperatingWithHours() {
            when(clinics.listAll()).thenReturn(List.of(
                    clinic(A, "VitalCare Test A", "OPERATING", "+84900000000"),
                    clinic(B, "VitalCare Test B", "INACTIVE", "+84900000001")));
            hourRows.add(new Object[] {A, 1, "07:30:00", "11:30:00"});
            hourRows.add(new Object[] {A, 1, "13:30:00", "16:30:00"});

            List<PublicResponses.Clinic> list = site.clinics();

            assertEquals(1, list.size());
            PublicResponses.Clinic a = list.getFirst();
            assertEquals(A, a.id());
            assertEquals("VitalCare Test A", a.name());
            assertEquals("+84900000000", a.phone());
            assertEquals("a@vitalcare.vn", a.email());
            assertEquals(List.of(new PublicResponses.Opening(1, LocalTime.of(7, 30), LocalTime.of(11, 30)),
                    new PublicResponses.Opening(1, LocalTime.of(13, 30), LocalTime.of(16, 30))), a.openingHours());
        }

        @Test void utc02_noHoursNoContacts() {
            when(clinics.listAll()).thenReturn(List.of(clinic(B, "VitalCare Test B", "OPERATING", null)));
            PublicResponses.Clinic b = site.clinics().getFirst();
            assertEquals(List.of(), b.openingHours());
            assertNull(b.phone());
            assertNull(b.email());
        }

        @Test void utc03_noClinics() {
            when(clinics.listAll()).thenReturn(List.of());
            assertEquals(List.of(), site.clinics());
        }

        @Test void utc04_hoursReadOnceForAll() {
            when(clinics.listAll()).thenReturn(List.of(clinic(A, "A", "OPERATING", null)));
            site.clinics();
            assertEquals(1, sqlSeen.size());
            assertFalse(sqlSeen.getFirst().contains(":clinicId"));
            assertTrue(sqlSeen.getFirst().contains("deleted_at is null"));
        }
    }

    @Nested
    @DisplayName("PublicSite.clinic(id)")
    class ClinicDetail {

        private Employee doctor(long id, String name, DayOfWeek... days) {
            Employee doctor = mock(Employee.class);
            User user = mock(User.class);
            when(user.getFullName()).thenReturn(name);
            when(doctor.getId()).thenReturn(id);
            when(doctor.getUser()).thenReturn(user);
            when(doctor.getSpecialty()).thenReturn("Nội khoa");
            when(doctor.getProfessionalTitle()).thenReturn("BSCKI");
            List<DoctorWorkingHours> week = new ArrayList<>();
            for (DayOfWeek day : days) {
                DoctorWorkingHours session = mock(DoctorWorkingHours.class);
                when(session.getDayOfWeek()).thenReturn(day);
                week.add(session);
            }
            when(availability.weekOf(doctor)).thenReturn(week);
            return doctor;
        }

        @Test void utc05_operatingWithDoctors() {
            when(clinics.get(A)).thenReturn(clinic(A, "VitalCare Test A", "OPERATING", "+84900000000"));
            hourRows.add(new Object[] {A, 2, "08:00:00", "12:00:00"});
            hourRows.add(new Object[] {B, 3, "08:00:00", "12:00:00"});
            Employee lan = doctor(1L, "Trần Thị Lan", DayOfWeek.WEDNESDAY, DayOfWeek.MONDAY, DayOfWeek.MONDAY);
            when(employees.doctorsOf(A)).thenReturn(List.of(lan));

            PublicResponses.ClinicDetail detail = site.clinic(A);

            assertEquals("VitalCare Test A", detail.name());
            assertEquals(List.of(new PublicResponses.Opening(2, LocalTime.of(8, 0), LocalTime.of(12, 0))),
                    detail.openingHours());
            PublicResponses.Doctor d = detail.doctors().getFirst();
            assertEquals("Trần Thị Lan", d.fullName());
            assertEquals("Nội khoa", d.specialty());
            assertEquals("BSCKI", d.professionalTitle());
            assertEquals(List.of(1, 3), d.workingDays());
        }

        @Test void utc06_notOperatingIsNotFound() {
            when(clinics.get(B)).thenReturn(clinic(B, "B", "INACTIVE", null));
            ResourceNotFoundException e = assertThrows(ResourceNotFoundException.class, () -> site.clinic(B));
            assertEquals("Clinic with id " + B + " was not found", e.getMessage());
            verify(employees, never()).doctorsOf(any());
        }

        @Test void utc07_unknownIsNotFound() {
            UUID unknown = UUID.randomUUID();
            when(clinics.get(unknown)).thenThrow(new ResourceNotFoundException("Clinic", unknown));
            assertThrows(ResourceNotFoundException.class, () -> site.clinic(unknown));
        }

        @Test void utc08_noDoctorsNoSessions() {
            when(clinics.get(A)).thenReturn(clinic(A, "A", "OPERATING", null));
            Employee minh = doctor(2L, "Lê Văn Minh");
            when(employees.doctorsOf(A)).thenReturn(List.of(minh));
            PublicResponses.ClinicDetail detail = site.clinic(A);
            assertEquals(List.of(), detail.openingHours());
            assertEquals(List.of(), detail.doctors().getFirst().workingDays());
        }
    }

    @Nested
    @DisplayName("PublicSite.services()")
    class Services {

        private MedicalService service(String name, ServiceCategory category, ServiceStatus status, long price) {
            MedicalService service = new MedicalService();
            service.setName(name);
            service.setDescription("Mô tả " + name);
            service.setCategory(category);
            service.setStatus(status);
            service.setPrice(BigDecimal.valueOf(price));
            service.setTurnaroundMinutes(30);
            return service;
        }

        @Test void utc09_onlyActive() {
            when(catalog.list()).thenReturn(List.of(
                    service("Khám nội", ServiceCategory.CONSULTATION, ServiceStatus.ACTIVE, 150000),
                    service("Siêu âm cũ", ServiceCategory.IMAGING, ServiceStatus.INACTIVE, 200000)));
            List<PublicResponses.Service> list = site.services();
            assertEquals(List.of("Khám nội"), list.stream().map(PublicResponses.Service::name).toList());
        }

        @Test void utc10_sortedByCategoryThenName() {
            when(catalog.list()).thenReturn(List.of(
                    service("Xét nghiệm máu", ServiceCategory.LAB, ServiceStatus.ACTIVE, 90000),
                    service("Khám tai mũi họng", ServiceCategory.CONSULTATION, ServiceStatus.ACTIVE, 150000),
                    service("Khám nội", ServiceCategory.CONSULTATION, ServiceStatus.ACTIVE, 150000)));
            assertEquals(List.of("Khám nội", "Khám tai mũi họng", "Xét nghiệm máu"),
                    site.services().stream().map(PublicResponses.Service::name).toList());
        }

        @Test void utc11_fieldsCarried() {
            when(catalog.list()).thenReturn(List.of(
                    service("Khám nội", ServiceCategory.CONSULTATION, ServiceStatus.ACTIVE, 270000)));
            PublicResponses.Service s = site.services().getFirst();
            assertEquals("Mô tả Khám nội", s.description());
            assertEquals(ServiceCategory.CONSULTATION, s.category());
            assertEquals(0, BigDecimal.valueOf(270000).compareTo(s.price()));
            assertEquals(30, s.turnaroundMinutes());
        }

        @Test void utc12_emptyCatalogue() {
            when(catalog.list()).thenReturn(List.of());
            assertEquals(List.of(), site.services());
        }

        @Test void utc13_allInactive() {
            when(catalog.list()).thenReturn(List.of(
                    service("Siêu âm cũ", ServiceCategory.IMAGING, ServiceStatus.INACTIVE, 200000)));
            assertEquals(List.of(), site.services());
        }
    }

    @Nested
    @DisplayName("PublicSiteController.firstAid / firstAidArticle")
    class FirstAidEndpoints {

        private FirstAidArticles firstAid;
        private PublicSiteController controller;

        @BeforeEach
        void setUp() {
            firstAid = mock(FirstAidArticles.class);
            controller = new PublicSiteController(site, firstAid);
        }

        private FirstAidArticleHistory step(long articleId, String title) {
            FirstAidArticleHistory step = mock(FirstAidArticleHistory.class);
            when(step.getArticleId()).thenReturn(articleId);
            when(step.getTitle()).thenReturn(title);
            when(step.getCategory()).thenReturn(FirstAidCategory.FEVER);
            when(step.getSummary()).thenReturn("Tóm tắt");
            when(step.getBody()).thenReturn("1. Bước một");
            when(step.getAt()).thenReturn(OffsetDateTime.parse("2026-10-30T08:00:00+07:00"));
            return step;
        }

        @Test void unknownCategoryIsEmpty() {
            assertEquals(List.of(), controller.firstAid(null, "xyz"));
            verify(firstAid, never()).published(any(), any());
        }

        @Test void searchAndCategoryPassedOn() {
            FirstAidArticleHistory s = step(3L, "Sốt cao ở trẻ");
            when(firstAid.published("sốt", FirstAidCategory.FEVER)).thenReturn(List.of(s));
            List<FirstAidResponses.Published> list = controller.firstAid("sốt", "fever");
            assertEquals(1, list.size());
            assertEquals(3L, list.getFirst().id());
            assertEquals("Sốt cao ở trẻ", list.getFirst().title());
        }

        @Test void noFilters() {
            when(firstAid.published(null, null)).thenReturn(List.of());
            assertEquals(List.of(), controller.firstAid(null, null));
            verify(firstAid).published(null, null);
        }

        @Test void oneArticle() {
            FirstAidArticleHistory s = step(5L, "Bỏng nhẹ");
            when(firstAid.publishedOne(5L)).thenReturn(s);
            FirstAidResponses.Published p = controller.firstAidArticle(5L);
            assertEquals(5L, p.id());
            assertEquals("Bỏng nhẹ", p.title());
        }

        @Test void oneArticleNotFound() {
            when(firstAid.publishedOne(99L)).thenThrow(new ResourceNotFoundException("First-aid article", 99L));
            ResourceNotFoundException e = assertThrows(ResourceNotFoundException.class,
                    () -> controller.firstAidArticle(99L));
            assertEquals("First-aid article with id 99 was not found", e.getMessage());
        }
    }
}
