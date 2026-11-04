package com.vn.vitalcare.identity.user.dto;

import com.vn.vitalcare.identity.user.entity.UserStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * {@code POST /users} payload.
 *
 * <p>The password is set by whoever creates the account, because there is no
 * self-registration and no mail server to send an invitation through. It is
 * accepted here in the clear over the transport and hashed before it reaches
 * the database — it is never stored, echoed or logged.
 */
public record UserRequest(
        /** The sign-in identifier. Stored as {@code +84...} whichever form arrives. */
        @NotBlank(message = "Phone number is required")
        // Mirrors what UserService accepts, so a number that can sign in can
        // also be enrolled. A pasted number almost always carries a trailing
        // space, and refusing it here while the sign-in form takes it is an
        // inconsistency nobody on the other end can act on.
        @Pattern(regexp = "^[\\s\\h.()-]*(?:\\+?84|0)(?:[\\s\\h.()-]*\\d){9}[\\s\\h.()-]*$",
                message = "Phone number must be a Vietnamese number, such as 0901234567 or +84901234567")
        String phone,

        /** Optional — nothing authenticates against it; it is for notifications. */
        @Email(message = "Email must be a valid address")
        @Size(max = 255, message = "Email must be at most 255 characters")
        String email,

        @NotBlank(message = "Full name is required")
        @Size(max = 255, message = "Full name must be at most 255 characters")
        String fullName,

        @NotBlank(message = "Password is required")
        // Length is the only rule worth enforcing here. Composition rules push
        // people towards predictable substitutions without adding real entropy,
        // and a longer minimum buys more than a required symbol does.
        @Size(min = 8, max = 100, message = "Password must be between 8 and 100 characters")
        String password,

        /** Optional; a user created without one starts active. */
        UserStatus status,

        @NotEmpty(message = "At least one role is required")
        @Valid
        List<RoleRef> roles) {
}
