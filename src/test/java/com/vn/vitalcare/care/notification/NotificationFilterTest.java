package com.vn.vitalcare.care.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.vn.vitalcare.entity.Notification;
import com.vn.vitalcare.entity.NotificationLevel;
import com.vn.vitalcare.identity.user.entity.User;
import com.vn.vitalcare.identity.user.entity.UserStatus;
import com.vn.vitalcare.share.web.ListParams;
import jakarta.persistence.EntityManager;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;

/** KAN-99 / KAN-100: the Notification Center's search box and advanced filter narrow the list. */
@SpringBootTest
@Transactional
class NotificationFilterTest {

    @Autowired EntityManager em;
    @Autowired Notifier notifier;

    @Test
    void searchAndFiltersNarrowTheCallersOwnNotifications() {
        User user = new User(UUID.randomUUID().toString().substring(0, 18), null, "hash", "Bệnh nhân", UserStatus.ACTIVE);
        em.persist(user);
        User other = new User(UUID.randomUUID().toString().substring(0, 18), null, "hash", "Người khác", UserStatus.ACTIVE);
        em.persist(other);
        Long offline = save(user, NotificationLevel.WARNING, "Thiết bị VC-DEMO-BN01 mất kết nối", false);
        Long abnormal = save(user, NotificationLevel.ERROR, "Chỉ số sức khỏe bất thường", false);
        Long readAbnormal = save(user, NotificationLevel.ERROR, "Chỉ số sức khỏe bất thường", true);
        Long paid = save(user, NotificationLevel.SUCCESS, "Đã thanh toán", true);
        save(other, NotificationLevel.ERROR, "Chỉ số sức khỏe bất thường", false);

        assertThat(ids(user, "q", "mất kết nối")).containsExactly(offline);
        assertThat(ids(user, "q", "BẤT THƯỜNG")).containsExactlyInAnyOrder(abnormal, readAbnormal);
        assertThat(ids(user, "q", "khongco123")).isEmpty();
        assertThat(ids(user)).containsExactlyInAnyOrder(offline, abnormal, readAbnormal, paid);

        assertThat(ids(user, "title_like", "bất thường", "level", "ERROR", "read", "false")).containsExactly(abnormal);
        assertThat(ids(user, "title_like", "bất thường", "level", "SUCCESS", "read", "false")).isEmpty();
        assertThat(ids(user, "level_ne", "ERROR")).containsExactlyInAnyOrder(offline, paid);
        assertThat(ids(user, "read", "true")).containsExactlyInAnyOrder(readAbnormal, paid);
        assertThat(ids(user, "read", "unread")).containsExactlyInAnyOrder(offline, abnormal);
        assertThat(ids(user, "level", "NOPE")).isEmpty();
    }

    private Long save(User owner, NotificationLevel level, String title, boolean read) {
        Notification n = new Notification(owner.getId(), level, "alert", title, null, null, OffsetDateTime.now());
        if (read) {
            n.markRead(OffsetDateTime.now());
        }
        em.persist(n);
        em.flush();
        return n.getId();
    }

    private List<Long> ids(User owner, String... pairs) {
        var query = new LinkedMultiValueMap<String, String>();
        for (int i = 0; i < pairs.length; i += 2) {
            query.add(pairs[i], pairs[i + 1]);
        }
        return notifier.own(owner.getId(), new ListParams(query)).map(Notification::getId).getContent();
    }
}
