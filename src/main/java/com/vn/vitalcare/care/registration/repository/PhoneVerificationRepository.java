package com.vn.vitalcare.care.registration.repository;

import com.vn.vitalcare.care.registration.entity.PhoneVerification;
import com.vn.vitalcare.care.registration.entity.VerificationPurpose;
import java.time.Instant;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PhoneVerificationRepository extends JpaRepository<PhoneVerification, Long> {

    /**
     * The newest code for a number, locked until the transaction ends.
     *
     * <p>Only the newest is ever accepted: asking for a new code retires the
     * old one, so a code read off an old message cannot be replayed after a
     * resend.
     *
     * <p>Without the lock, guesses sent in parallel all read the same attempt
     * count and all write it back plus one: two hundred concurrent guesses
     * cost one attempt, and the limit that makes six digits safe is gone.
     * Locked, they queue, and each one sees the count the previous one left.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from PhoneVerification v where v.phone = :phone and v.purpose = :purpose "
            + "order by v.createdAt desc limit 1")
    Optional<PhoneVerification> findLatestForUpdate(
            @Param("phone") String phone, @Param("purpose") VerificationPurpose purpose);

    /** Codes sent to a number since a moment — the per-hour resend limit. */
    long countByPhoneAndPurposeAndCreatedAtAfter(String phone, VerificationPurpose purpose, Instant since);
}
