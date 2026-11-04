package com.vn.vitalcare.care.chat;

import com.vn.vitalcare.entity.ChatSenderKind;
import com.vn.vitalcare.entity.Message;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Messages, always through a conversation somebody has already been allowed
 * into: nothing here decides who may read what.
 */
public interface MessageRepository extends JpaRepository<Message, Long> {

    /** The latest messages of a conversation, newest first; the caller turns them round. */
    @Query("select m from Message m where m.conversation.id = :conversationId and m.deletedAt is null order by m.id desc")
    List<Message> findLatest(@Param("conversationId") Long conversationId, Pageable page);

    @Query("select m from Message m where m.id = :id and m.conversation.id = :conversationId and m.deletedAt is null")
    Optional<Message> findIn(@Param("conversationId") Long conversationId, @Param("id") Long id);

    /**
     * Per conversation, how many messages the clinic's side has not read:
     * those the other side wrote after staff last opened it.
     */
    @Query("select m.conversation.id, count(m) from Message m join m.conversation c "
            + "where c.id in :ids and m.deletedAt is null and m.senderKind <> :staff "
            + "and (c.staffReadAt is null or m.sentAt > c.staffReadAt) group by m.conversation.id")
    List<Object[]> countUnreadByStaff(@Param("ids") Collection<Long> ids, @Param("staff") ChatSenderKind staff);

    /** Per conversation, how many staff messages the customer or guest has not read. */
    @Query("select m.conversation.id, count(m) from Message m join m.conversation c "
            + "where c.id in :ids and m.deletedAt is null and m.senderKind = :staff "
            + "and (c.customerReadAt is null or m.sentAt > c.customerReadAt) group by m.conversation.id")
    List<Object[]> countUnreadByCustomer(@Param("ids") Collection<Long> ids, @Param("staff") ChatSenderKind staff);
}
