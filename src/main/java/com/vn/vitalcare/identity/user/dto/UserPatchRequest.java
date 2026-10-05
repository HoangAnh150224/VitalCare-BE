package com.vn.vitalcare.identity.user.dto;

import com.vn.vitalcare.identity.user.entity.UserStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * {@code PATCH /users/{id}} payload — a {@code null} field means "leave it
 * unchanged".
 *
 * <p>There is deliberately no password field. Changing someone else's password
 * is a different act from editing their profile, with a different permission
 * story and a different audit meaning, so it has its own endpoint
 * ({@code POST /users/{id}/password}) rather than hiding inside a form save.
 */
public record UserPatchRequest(
        // Same shape as UserRequest, and as what UserService accepts.
        @Pattern(regexp = "^[\\s\\h.()-]*(?:\\+?84|0)(?:[\\s\\h.()-]*\\d){9}[\\s\\h.()-]*$",
                message = "Phone number must be a Vietnamese number, such as 0901234567 or +84901234567")
        String phone,

        // Omitting the field leaves the address alone; sending an empty string
        // removes it, which is how the edit screen clears one. Both @Email and
        // @Size accept "", so the meaning is given by the service rather than
        // here.
        @Email(message = "Email must be a valid address")
        @Size(max = 255, message = "Email must be at most 255 characters")
        String email,

        @Size(min = 1, max = 255, message = "Full name must be between 1 and 255 characters")
        String fullName,

        UserStatus status,

        // Size rather than NotEmpty, because null and empty have to mean
        // different things here: omitting the field leaves the assignments
        // alone, while an empty list is rejected. NotEmpty would reject both.
        // An account with no role can sign in and do nothing, which reads as a
        // broken system rather than a deliberate one -- disabling the account
        // is what that is for.
        @Size(min = 1, message = "At least one role is required")
        @Valid
        List<RoleRef> roles) {
}
