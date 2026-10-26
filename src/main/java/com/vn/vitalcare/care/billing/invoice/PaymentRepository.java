package com.vn.vitalcare.care.billing.invoice;

import com.vn.vitalcare.entity.Payment;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Payment attempts. Reached through their bill, or by the gateway's reference. */
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    /** The attempt a gateway answer is about, locked: the return and the IPN may arrive together. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p join fetch p.invoice where p.transactionCode = :code and p.deletedAt is null")
    Optional<Payment> findByTransactionCodeForUpdate(@Param("code") String code);
}
