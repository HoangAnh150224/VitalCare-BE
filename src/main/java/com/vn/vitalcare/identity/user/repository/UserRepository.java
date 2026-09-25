package com.vn.vitalcare.identity.user.repository;

import com.vn.vitalcare.identity.user.entity.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {

    Optional<User> findByUsernameIgnoreCase(String username);

    Optional<User> findByEmailIgnoreCase(String email);

    /**
     * Resolves the identifier typed into the sign-in form, which may be either.
     *
     * <p>Accepting both is a convenience for the person signing in; it is safe
     * because {@code username} and {@code email} are each unique and neither
     * can be blank, so no single value can address two accounts.
     */
    @Query("select u from User u where lower(u.username) = lower(:identifier) "
            + "or lower(u.email) = lower(:identifier)")
    Optional<User> findByUsernameOrEmail(@Param("identifier") String identifier);

    /**
     * How many active accounts hold a given role.
     *
     * <p>Used to refuse the last administrator being demoted or disabled: a
     * system with nobody able to manage users is one that needs a database
     * client to repair.
     */
    @Query("select count(u) from User u join u.roles r "
            + "where r.code = :roleCode and u.status = com.vn.vitalcare.identity.user.entity.UserStatus.ACTIVE")
    long countActiveWithRole(@Param("roleCode") String roleCode);
}
