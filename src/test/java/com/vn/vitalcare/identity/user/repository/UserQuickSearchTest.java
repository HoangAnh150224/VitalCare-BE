package com.vn.vitalcare.identity.user.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.vn.vitalcare.identity.user.entity.User;
import com.vn.vitalcare.identity.user.entity.UserStatus;
import com.vn.vitalcare.share.web.ListParams;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;

@SpringBootTest
@Transactional
class UserQuickSearchTest {

    @Autowired
    private UserRepository users;

    @Test
    void deletedPhoneCanBelongToANewIdentity() {
        String phone = UUID.randomUUID().toString().substring(0, 18);
        User archived = new User(phone, null, "old-hash", "Archived", UserStatus.INACTIVE);
        archived.setDeletedAt(java.time.Instant.now());
        users.saveAndFlush(archived);
        assertThat(users.findByPhone(phone)).isEmpty();

        User replacement = users.saveAndFlush(new User(phone, null, "new-hash", "New", UserStatus.ACTIVE));
        assertThat(replacement.getId()).isNotEqualTo(archived.getId());
        assertThat(users.findByPhone(phone)).contains(replacement);
        assertThat(users.findById(archived.getId())).contains(archived);
    }

    @Test
    void deletionFiltersIncludeArchivedUsersOnlyWhenRequested() {
        String marker = UUID.randomUUID().toString();
        User current = users.saveAndFlush(new User(
                marker.substring(0, 12) + "a", null, "hash", marker, UserStatus.INACTIVE));
        User deleted = new User(marker.substring(0, 12) + "b", null, "hash", marker, UserStatus.INACTIVE);
        deleted.setDeletedAt(java.time.Instant.now());
        users.saveAndFlush(deleted);
        LinkedMultiValueMap<String, String> query = new LinkedMultiValueMap<>();
        query.add("q", marker);
        assertThat(users.findAll(UserSpecifications.from(new ListParams(query))))
                .extracting(User::getId).containsExactly(current.getId());
        query.set("status", "inactive");
        assertThat(users.findAll(UserSpecifications.from(new ListParams(query))))
                .extracting(User::getId).containsExactlyInAnyOrder(current.getId(), deleted.getId());
        query.set("deletionState", "current");
        assertThat(users.findAll(UserSpecifications.from(new ListParams(query))))
                .extracting(User::getId).containsExactly(current.getId());
        query.set("deletionState", "deleted");
        assertThat(users.findAll(UserSpecifications.from(new ListParams(query))))
                .extracting(User::getId).containsExactly(deleted.getId());
        query.set("deletionState", "all");
        assertThat(users.findAll(UserSpecifications.from(new ListParams(query))))
                .extracting(User::getId).containsExactlyInAnyOrder(current.getId(), deleted.getId());
    }

    @Test
    void quickSearchMatchesNumericIdAndStillMatchesPhoneDigits() {
        String salt = UUID.randomUUID().toString().replace("-", "");
        User byId = users.saveAndFlush(new User(
                "+84" + salt.substring(0, 9), null, "hash", "Search target", UserStatus.ACTIVE));

        String idDigits = byId.getId().toString();
        String idPhoneTail = "000000000";
        for (char digit = '0'; idPhoneTail.contains(idDigits) && digit <= '9'; digit++) {
            idPhoneTail = String.valueOf(digit).repeat(9);
        }
        byId.setPhone("+84" + idPhoneTail);
        users.saveAndFlush(byId);

        LinkedMultiValueMap<String, String> idQuery = new LinkedMultiValueMap<>();
        idQuery.add("q", idDigits);
        List<User> idResults = users.findAll(UserSpecifications.from(new ListParams(idQuery)));
        assertThat(idResults).extracting(User::getId).contains(byId.getId());

        String phonePrefix = String.format("%07d", Math.floorMod(System.nanoTime(), 10_000_000L));
        User byPhone = users.saveAndFlush(new User(
                "+84" + phonePrefix + "73", null, "hash", "Phone match", UserStatus.ACTIVE));

        LinkedMultiValueMap<String, String> phoneQuery = new LinkedMultiValueMap<>();
        phoneQuery.add("q", "73");
        List<User> phoneResults = users.findAll(UserSpecifications.from(new ListParams(phoneQuery)));

        assertThat(phoneResults)
                .extracting(User::getId)
                .contains(byPhone.getId());
    }
}
