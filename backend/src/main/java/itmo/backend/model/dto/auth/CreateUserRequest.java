package itmo.backend.model.dto.auth;

import itmo.backend.model.entity.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
    @NotBlank(message = "username must not be blank")
    @Size(min = 3, max = 50, message = "username must be between 3 and 50 characters")
    String username,

    @NotBlank(message = "email must not be blank")
    @Email(message = "email must be a valid email address")
    String email,

    @NotBlank(message = "password must not be blank")
    @Size(min = 6, message = "password must be at least 6 characters")
    String password,

    @NotNull(message = "role must not be null")
    UserRole role
) {
}
