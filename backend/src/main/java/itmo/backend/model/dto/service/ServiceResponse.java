package itmo.backend.model.dto.registry;

import java.time.Instant;
import java.util.UUID;

public record ServiceResponse(
    UUID id,
    String name,
    String description,
    ServiceType serviceType,
    ServiceStatus status,
    String url,
    UUID vmId,
    Instant createdAt,
    Instant updatedAt
) {
}