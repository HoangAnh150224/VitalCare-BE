package com.vn.vitalcare.identity.user.repository;

import com.vn.vitalcare.identity.user.entity.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {

    /**
     * Resolves the number typed into the sign-in form.
     *
     * <p>Takes an already-normalised E.164 value — {@code UserService} converts
     * before calling, which is what lets this be an exact match rather than a
     * guess across spellings. No {@code IgnoreCase} variant: the column holds
     * digits and a leading {@code +}.
     */
    Optional<User> findByPhone(String phone);

    Optional<User> findByEmailIgnoreCase(String email);

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
