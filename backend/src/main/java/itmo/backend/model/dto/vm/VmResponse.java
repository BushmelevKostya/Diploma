package itmo.backend.model.dto.vm;

import itmo.backend.model.entity.VmStatus;
import java.time.Instant;
import java.util.UUID;

public record VmResponse(
    UUID id,
    String name,
    String hostname,
    String ipAddress,
    String statusMessage,
    Integer vcpu,
    Integer memoryMb,
    Integer diskSizeGb,
    String osImage,
    VmStatus status,
    UUID createdBy,
    Instant createdAt,
    Instant updatedAt
) {
}
