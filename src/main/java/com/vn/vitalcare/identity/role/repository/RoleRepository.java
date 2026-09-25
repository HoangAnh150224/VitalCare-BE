package com.vn.vitalcare.identity.role.repository;

import com.vn.vitalcare.identity.role.entity.Role;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface RoleRepository extends JpaRepository<Role, Long>, JpaSpecificationExecutor<Role> {

    /**
     * Used to reject a duplicate code before the unique index does.
     *
     * <p>The index is still what guarantees uniqueness — this only turns the
     * common case into a message naming the field, rather than a 409 about a
     * constraint the person filling in the form has never heard of.
     */
    Optional<Role> findByCodeIgnoreCase(String code);

    /**
     * Deleting a role that users still hold is refused by the {@code RESTRICT}
     * foreign key on {@code user_roles}, not by a count query here: asking how
     * many users hold a role from inside the role domain would mean reading
     * the identity.user table directly, which is the coupling this package
     * layout exists to prevent. The database answers it instead, and
     * {@code ApiExceptionHandler} turns that into a 409.
     */
}
