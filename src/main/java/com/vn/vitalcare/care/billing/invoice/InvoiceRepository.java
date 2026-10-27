package com.vn.vitalcare.care.billing.invoice;

import com.vn.vitalcare.entity.Invoice;
import com.vn.vitalcare.share.data.SecuredRepository;
import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Bills. Reads for people go through a scope; the unscoped ones below are for
 * paths that have already established access some other way (the encounter
 * being concluded, the customer's own appointment, the gateway's callback).
 */
public interface InvoiceRepository extends SecuredRepository<Invoice, Long> {

    @Override
    @EntityGraph(attributePaths = {"customer", "customer.user", "clinic"})
    Page<Invoice> findAll(Specification<Invoice> spec, Pageable pageable);

    @Override
    @EntityGraph(attributePaths = {"customer", "customer.user", "clinic"})
    Optional<Invoice> findOne(Specification<Invoice> spec);

    /**
     * Every bill of one account: the owner's and their relatives', newest first.
     * Not scoped: pinned to the caller's own profile, as {@code AppointmentRepository.findOwn}.
     */
    @EntityGraph(attributePaths = {"customer", "clinic"})
    @Query("select i from Invoice i join i.customer c left join c.guardian g "
            + "where (c.id = :ownerId or g.id = :ownerId) and i.deletedAt is null order by i.issuedAt desc, i.id desc")
    List<Invoice> findOwn(@Param("ownerId") Long ownerCustomerId);

    @Query("select i from Invoice i where i.encounter.id = :encounterId and i.deletedAt is null")
    Optional<Invoice> findByEncounter(@Param("encounterId") Long encounterId);

    @Query("select i from Invoice i where i.appointment.id = :appointmentId and i.deletedAt is null "
            + "order by i.id desc limit 1")
    Optional<Invoice> findByAppointment(@Param("appointmentId") Long appointmentId);

    /** The bill, locked: what any change of its status takes first, so it is settled once. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invoice i where i.id = :id and i.deletedAt is null")
    Optional<Invoice> findForUpdate(@Param("id") Long id);

    /** A clinic's bills issued in a period, with their lines, for the revenue report. */
    @Query("select distinct i from Invoice i left join fetch i.items it left join fetch it.service "
            + "where i.clinicId = :clinicId and i.issuedAt >= :from and i.issuedAt < :to and i.deletedAt is null "
            + "and i.status <> com.vn.vitalcare.entity.InvoiceStatus.CANCELLED order by i.issuedAt")
    List<Invoice> findIssuedBetween(@Param("clinicId") UUID clinicId, @Param("from") OffsetDateTime from,
                                    @Param("to") OffsetDateTime to);
}
