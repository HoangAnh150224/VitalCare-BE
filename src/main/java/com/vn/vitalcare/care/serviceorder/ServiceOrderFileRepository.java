package com.vn.vitalcare.care.serviceorder;

import com.vn.vitalcare.entity.ServiceOrderFile;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Files of a result. Reached only through their order, which is read through its scope first. */
public interface ServiceOrderFileRepository extends JpaRepository<ServiceOrderFile, Long> {

    @Query("select f from ServiceOrderFile f where f.id = :id and f.order.id = :orderId and f.deletedAt is null")
    Optional<ServiceOrderFile> findOfOrder(@Param("orderId") Long orderId, @Param("id") Long id);
}
