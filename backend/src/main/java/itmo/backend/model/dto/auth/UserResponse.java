package itmo.backend.model.dto.auth;

import itmo.backend.model.entity.UserRole;
import java.time.Instant;
import java.util.UUID;

public record UserResponse(
    UUID id,
    String username,
    String email,
    UserRole role,
    Instant createdAt,
    Instant updatedAt
) {
}
