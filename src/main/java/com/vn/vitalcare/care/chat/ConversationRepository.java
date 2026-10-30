package com.vn.vitalcare.care.chat;

import com.vn.vitalcare.entity.Conversation;
import com.vn.vitalcare.share.data.SecuredRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Conversations. Staff reach them through their row-level scope; the three
 * queries declared here are the other two sides' doors, each pinned to who is
 * asking: a customer's own account, or a guest's token (checked by the caller
 * against the hash on the row it finds).
 */
public interface ConversationRepository extends SecuredRepository<Conversation, Long> {

    @Override
    @EntityGraph(attributePaths = {"clinic"})
    Page<Conversation> findAll(Specification<Conversation> spec, Pageable pageable);

    @Override
    @EntityGraph(attributePaths = {"clinic"})
    Optional<Conversation> findOne(Specification<Conversation> spec);

    /** A guest's conversation by id; the token is compared by the caller. */
    @Query("select c from Conversation c left join fetch c.clinic "
            + "where c.id = :id and c.customerUserId is null and c.guestTokenHash is not null and c.deletedAt is null")
    Optional<Conversation> findGuest(@Param("id") Long id);

    /** A customer's conversations, latest first. */
    @Query("select c from Conversation c left join fetch c.clinic "
            + "where c.customerUserId = :userId and c.deletedAt is null "
            + "order by c.lastMessageAt desc nulls last, c.id desc")
    List<Conversation> findOwn(@Param("userId") Long userId);

    @Query("select c from Conversation c left join fetch c.clinic "
            + "where c.id = :id and c.customerUserId = :userId and c.deletedAt is null")
    Optional<Conversation> findOwn(@Param("id") Long id, @Param("userId") Long userId);

    /** The customer's conversation with a clinic, if there is one: writing to a clinic again continues it. */
    @Query("select c from Conversation c where c.customerUserId = :userId and c.clinicId = :clinicId "
            + "and c.deletedAt is null order by c.id desc limit 1")
    Optional<Conversation> findOwnAt(@Param("userId") Long userId, @Param("clinicId") UUID clinicId);
}
