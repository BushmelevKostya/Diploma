package itmo.backend.model.dto.vm;

import itmo.backend.model.entity.VmStatus;
import java.time.Instant;
import java.util.UUID;

public record VmResponse(
    UUID id,
    String name,
    String ipAddress,
    VmStatus status,
    Integer cpuCores,
    Integer memoryMb,
    Integer diskGb,
    Instant createdAt
) {
}
