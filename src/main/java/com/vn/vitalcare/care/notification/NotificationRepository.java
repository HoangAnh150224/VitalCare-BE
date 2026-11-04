package com.vn.vitalcare.care.notification;

import com.vn.vitalcare.entity.Notification;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Every read is pinned to one account: nobody sees anybody else's notifications. */
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    @Query("select n from Notification n where n.userId = :userId and n.deletedAt is null "
            + "and (:unread = false or n.readAt is null) order by n.createdAt desc, n.id desc")
    Page<Notification> findOwn(@Param("userId") Long userId, @Param("unread") boolean unreadOnly, Pageable page);

    @Query("select n from Notification n where n.userId = :userId and n.deletedAt is null order by n.createdAt desc, n.id desc")
    List<Notification> findRecent(@Param("userId") Long userId, Pageable page);

    @Query("select count(n) from Notification n where n.userId = :userId and n.readAt is null and n.deletedAt is null")
    long countUnread(@Param("userId") Long userId);

    @Query("select n from Notification n where n.id = :id and n.userId = :userId and n.deletedAt is null")
    Optional<Notification> findOwn(@Param("id") Long id, @Param("userId") Long userId);

    @Modifying
    @Query("update Notification n set n.readAt = :now where n.userId = :userId and n.readAt is null and n.deletedAt is null")
    int markAllRead(@Param("userId") Long userId, @Param("now") OffsetDateTime now);
}
