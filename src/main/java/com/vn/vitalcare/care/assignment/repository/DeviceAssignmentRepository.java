package com.vn.vitalcare.care.assignment.repository;

import com.vn.vitalcare.entity.DeviceAssignment;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Device-on-patient rows. "Open" means the device has not come back
 * ({@code unassignedAt} null) — what the partial unique indexes in 016 hold to.
 */
public interface DeviceAssignmentRepository extends JpaRepository<DeviceAssignment, Long> {

    /** The devices a patient has worn, newest first. */
    @EntityGraph(attributePaths = "device")
    List<DeviceAssignment> findByCustomerIdAndDeletedAtIsNullOrderByAssignedAtDesc(Long customerId);

    @EntityGraph(attributePaths = "device")
    Optional<DeviceAssignment> findFirstByCustomerIdAndUnassignedAtIsNullAndDeletedAtIsNull(Long customerId);

    @EntityGraph(attributePaths = "device")
    Optional<DeviceAssignment> findByIdAndCustomerIdAndDeletedAtIsNull(Long id, Long customerId);

    /** Who wore a device, newest first — its history. */
    @EntityGraph(attributePaths = {"customer", "customer.user"})
    List<DeviceAssignment> findByDeviceIdAndDeletedAtIsNullOrderByAssignedAtDesc(Long deviceId);

    /** The open assignment of each device in a page of devices, in one query. */
    @EntityGraph(attributePaths = {"customer", "customer.user"})
    List<DeviceAssignment> findByDeviceIdInAndUnassignedAtIsNullAndDeletedAtIsNull(Collection<Long> deviceIds);

    /** The device each of a page of patients wears now, in one query. */
    @EntityGraph(attributePaths = "device")
    List<DeviceAssignment> findByCustomerIdInAndUnassignedAtIsNullAndDeletedAtIsNull(Collection<Long> customerIds);

    boolean existsByDeviceIdAndUnassignedAtIsNullAndDeletedAtIsNull(Long deviceId);
}
