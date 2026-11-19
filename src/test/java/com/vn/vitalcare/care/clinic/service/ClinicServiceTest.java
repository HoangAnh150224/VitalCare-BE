package com.vn.vitalcare.care.clinic.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.care.clinic.ClinicProperties;
import com.vn.vitalcare.care.clinic.dto.ClinicRequest;
import com.vn.vitalcare.care.clinic.repository.ClinicRepository;
import com.vn.vitalcare.care.clinic.service.impl.ClinicServiceImpl;
import com.vn.vitalcare.entity.Clinic;
import com.vn.vitalcare.share.audit.AuditEvent;
import com.vn.vitalcare.share.exception.FieldValidationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** KAN-44 phone format, KAN-45 unique name, KAN-49 audit of create / update / status change. */
class ClinicServiceTest {

    private static final UUID ID = UUID.randomUUID();
    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private ClinicRepository repository;
    private final List<Object> events = new ArrayList<>();
    private ClinicService service;
    private Clinic clinic;

    @BeforeEach
    void setUp() {
        repository = mock(ClinicRepository.class);
        when(repository.save(any(Clinic.class))).thenAnswer(invocation -> invocation.getArgument(0));
        clinic = new Clinic();
        clinic.setClinicId(ID);
        clinic.setClinicName("Cơ sở 2");
        clinic.setOperatingStatus("OPERATING");
        when(repository.findByClinicIdAndDeletedAtIsNull(ID)).thenReturn(Optional.of(clinic));
        when(repository.findForUpdate(ID)).thenReturn(Optional.of(clinic));
        service = new ClinicServiceImpl(repository, new ClinicProperties(ZoneId.of("Asia/Ho_Chi_Minh"), 30),
                Clock.systemUTC(), events::add);
    }

    private static ClinicRequest request(String name, String phone, String status) {
        return new ClinicRequest(name, "1 Demo Street", phone, "a@b.vn", "Office", status);
    }

    @Test
    void phoneMustBeAVietnameseNumber() {
        for (String ok : List.of("0901234567", "+84901234567", "+84 28 3822 1234", "028.3822.1234", "84901234567")) {
            assertTrue(VALIDATOR.validate(request("X", ok, "OPERATING")).isEmpty(), ok);
        }
        for (String bad : List.of("1234abcd", "090123", "090123456789", "+840901234567", "abc")) {
            assertTrue(VALIDATOR.validate(request("X", bad, "OPERATING")).stream()
                    .anyMatch(v -> v.getMessage().startsWith("Số điện thoại không hợp lệ")), bad);
        }
    }

    @Test
    void createRefusesATakenNameUnderTheNameField() {
        when(repository.existsByClinicNameIgnoreCaseAndDeletedAtIsNull("VitalCare Clinic")).thenReturn(true);
        FieldValidationException e = assertThrows(FieldValidationException.class,
                () -> service.create(request("  VitalCare Clinic ", "0901234567", "OPERATING")));
        assertEquals("name", e.field());
        assertEquals("Tên cơ sở đã tồn tại. Hãy đặt tên khác.", e.getMessage());
        verify(repository, never()).save(any());
        assertTrue(events.isEmpty());
    }

    @Test
    void updateRefusesAnotherClinicsNameButKeepsItsOwn() {
        when(repository.existsByClinicNameIgnoreCaseAndDeletedAtIsNullAndClinicIdNot("VitalCare Clinic", ID))
                .thenReturn(true);
        assertThrows(FieldValidationException.class,
                () -> service.update(ID, request("VitalCare Clinic", "0901234567", "OPERATING")));

        service.update(ID, request("Cơ sở 2", "0901234567", "INACTIVE"));
        AuditEvent event = (AuditEvent) events.getFirst();
        assertEquals("clinics", event.entityType());
        assertEquals("update", event.action());
        assertEquals("OPERATING", event.before().get("operatingStatus"));
        assertEquals("INACTIVE", event.after().get("operatingStatus"));
        assertEquals(ID.toString(), event.after().get("clinicId"));
    }

    @Test
    void createAndStatusChangeAreAudited() {
        service.create(request("Cơ sở 3", "0901234567", "OPERATING"));
        service.changeOperatingStatus(ID, "INACTIVE");
        assertEquals(List.of("create", "status_change"),
                events.stream().map(e -> ((AuditEvent) e).action()).toList());
    }
}
