package itmo.backend.model.dto.snapshot;

import java.time.Instant;
import java.util.UUID;

public record SnapshotResponse(
    UUID id,
    String name,
    String description,
    SnapshotStatus status,
    UUID vmId,
    Long sizeBytes,
    Instant createdAt
) {
}