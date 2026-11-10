package com.vn.vitalcare.identity.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vn.vitalcare.care.customer.service.CustomerService;
import com.vn.vitalcare.identity.user.entity.User;
import com.vn.vitalcare.identity.user.entity.UserStatus;
import com.vn.vitalcare.identity.user.repository.UserRepository;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import com.vn.vitalcare.share.web.ListParams;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;

@SpringBootTest
@Transactional
class UserDeletionTest {

    @Autowired
    private UserRepository users;

    @Autowired
    private UserService service;

    @Autowired
    private CustomerService customers;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void deletingAnAccountWithCustomerHistoryKeepsTheHistoryAndBlocksSignIn() {
        String suffix = "%09d".formatted(Math.floorMod(System.nanoTime(), 1_000_000_000L));
        String nationalPhone = "0" + suffix;
        User account = users.saveAndFlush(new User(
                "+84" + suffix, null, "hash", "Delete test account", UserStatus.ACTIVE));
        Long customerId = customers.createNeutral(account).getId();

        service.delete(account.getId());
        users.flush();

        assertThat(jdbc.queryForObject(
                "select status from users where id = ?", String.class, account.getId()))
                .isEqualTo("INACTIVE");
        assertThat(jdbc.queryForObject(
                "select deleted_at is not null from users where id = ?", Boolean.class, account.getId()))
                .isTrue();
        assertThat(jdbc.queryForObject(
                "select count(*) from customer where id = ? and user_id = ?",
                Integer.class, customerId, account.getId()))
                .isEqualTo(1);
        assertThat(service.list(new ListParams(new LinkedMultiValueMap<>())).getContent())
                .extracting(User::getId)
                .doesNotContain(account.getId());
        assertThat(service.findForAuthentication(nationalPhone))
                .hasValueSatisfying(found -> assertThat(found.canSignIn()).isFalse());
        assertThatThrownBy(() -> service.get(account.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
