package itmo.backend.model.dto.deployment;

import java.time.Instant;
import java.util.UUID;

public record DeploymentResponse(
    UUID id,
    String name,
    String version,
    String image,
    Integer replicas,
    Integer port,
    DeploymentStatus status,
    UUID vmId,
    UUID deployedBy,
    Instant createdAt,
    Instant updatedAt
) {
}