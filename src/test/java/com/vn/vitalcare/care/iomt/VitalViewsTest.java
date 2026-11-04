package com.vn.vitalcare.care.iomt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.care.assignment.repository.DeviceAssignmentRepository;
import com.vn.vitalcare.care.customer.service.CustomerService;
import com.vn.vitalcare.entity.Customer;
import com.vn.vitalcare.entity.DeviceAssignment;
import com.vn.vitalcare.entity.MedicalDevice;
import com.vn.vitalcare.share.exception.FieldValidationException;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** FE-04: whose readings are read, and over what window. */
class VitalViewsTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 11, 2, 9, 0, 0, 0, ZoneOffset.ofHours(7));

    private VitalSignsRepository vitals;
    private DeviceAssignmentRepository assignments;
    private CustomerService customers;
    private VitalViews views;

    @BeforeEach
    void setUp() {
        vitals = mock(VitalSignsRepository.class);
        assignments = mock(DeviceAssignmentRepository.class);
        customers = mock(CustomerService.class);
        views = new VitalViews(vitals, assignments, customers, mock(com.vn.vitalcare.care.staff.service.ClinicScope.class), Clock.fixed(Instant.from(NOW), ZoneOffset.UTC));
    }

    private static Customer customer(long id) {
        Customer c = new Customer();
        ReflectionTestUtils.setField(c, "id", id);
        return c;
    }

    private static DeviceAssignment worn(Customer c, long deviceId) {
        MedicalDevice d = new MedicalDevice();
        ReflectionTestUtils.setField(d, "id", deviceId);
        d.setDeviceCode("VC-" + deviceId);
        DeviceAssignment a = new DeviceAssignment();
        a.setCustomer(c);
        a.setDevice(d);
        return a;
    }

    @Test
    @DisplayName("The overview lists only the wearers the caller may see")
    void overviewFollowsTheCallersScope() {
        Customer mine = customer(37), theirs = customer(99);
        when(assignments.findOpenWithPatients(any(), any())).thenReturn(List.of(worn(mine, 1), worn(theirs, 2)));
        when(customers.getMany(List.of(37L, 99L))).thenReturn(List.of(mine));
        when(vitals.findLatest(any())).thenReturn(List.of());

        assertThat(views.monitored()).extracting(VitalViewDtos.Monitored::customerId).containsExactly(37L);
    }

    @Test
    @DisplayName("A patient outside the caller's scope is not found, and nothing is read for them")
    void outOfScopeIsNotFound() {
        when(customers.get(99L)).thenThrow(new ResourceNotFoundException("Customer", 99L));

        assertThatThrownBy(() -> views.patient(99L)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> views.patient(null)).isInstanceOf(FieldValidationException.class);
        verify(vitals, never()).findLatest(any());
    }

    @Test
    @DisplayName("Trends are hourly up to two days, daily beyond; windows are checked")
    void trendBuckets() {
        Customer c = customer(37);
        when(vitals.findTrend(eq(37L), anyString(), any(), any())).thenReturn(List.of());

        assertThat(views.trend(c, NOW.minusHours(24).toString(), NOW.toString()).unit()).isEqualTo("hour");
        assertThat(views.trend(c, NOW.minusDays(30).toString(), NOW.toString()).unit()).isEqualTo("day");
        // Without a window: the last seven days, by day from the first one's local midnight.
        assertThat(views.trend(c, null, null).from().toInstant())
                .isEqualTo(OffsetDateTime.of(2026, 10, 26, 0, 0, 0, 0, ZoneOffset.ofHours(7)).toInstant());
        assertThatThrownBy(() -> views.trend(c, NOW.toString(), NOW.minusDays(1).toString()))
                .isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> views.trend(c, NOW.minusDays(200).toString(), NOW.toString()))
                .isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> views.trend(c, "yesterday", null)).isInstanceOf(FieldValidationException.class);
    }

    @Test
    @DisplayName("A patient's own readings: their profile, or a dependant's; anyone else's is refused")
    void ownProfiles() {
        Customer me = customer(37), child = customer(38);
        when(customers.getForCurrentUser()).thenReturn(me);
        when(customers.ownProfile(me, 38L)).thenReturn(child);
        when(customers.ownProfile(me, 5L)).thenThrow(new ResourceNotFoundException("Profile", 5L));

        assertThat(views.ownProfile(null)).isSameAs(me);
        assertThat(views.ownProfile(38L)).isSameAs(child);
        assertThatThrownBy(() -> views.ownProfile(5L)).isInstanceOf(ResourceNotFoundException.class);
    }
}
