package com.vn.vitalcare.care.registration.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One code sent to one number.
 *
 * <p>Holds a SHA-256 of the code, never the code itself — see
 * {@code 011-create-phone-verification} for what that does and does not buy.
 * The same shape as {@code RefreshToken}: a recorded secret, usable until it
 * is spent, expires or is burned.
 */
@Entity
@Table(name = "phone_verification")
public class PhoneVerification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private VerificationPurpose purpose;

    @Column(name = "code_hash", nullable = false, length = 64)
    private String codeHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PhoneVerification() {
        // for JPA
    }

    public PhoneVerification(String phone, VerificationPurpose purpose, String codeHash,
                             Instant createdAt, Instant expiresAt) {
        this.phone = phone;
        this.purpose = purpose;
        this.codeHash = codeHash;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    /** True while a correct code would still be accepted. */
    public boolean isUsable(Instant now, int maxAttempts) {
        return consumedAt == null && attempts < maxAttempts && expiresAt.isAfter(now);
    }

    public void recordFailedAttempt() {
        attempts++;
    }

    public void consume(Instant when) {
        if (consumedAt == null) {
            consumedAt = when;
        }
    }

    public Long getId() {
        return id;
    }

    public String getPhone() {
        return phone;
    }

    public VerificationPurpose getPurpose() {
        return purpose;
    }

    public String getCodeHash() {
        return codeHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getConsumedAt() {
        return consumedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
