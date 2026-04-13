package itmo.backend.model.dto.auth;

import itmo.backend.model.entity.UserRole;
import jakarta.validation.constraints.Email;

public record UpdateUserRequest(
    @Email(message = "email must be a valid email address")
    String email,

    UserRole role
) {
}
