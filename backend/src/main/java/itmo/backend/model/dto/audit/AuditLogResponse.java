package itmo.backend.model.dto.audit;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AuditLogResponse(
    UUID id,
    UUID userId,
    String username,
    AuditAction action,
    String entityType,
    UUID entityId,
    Map<String, Object> details,
    Instant createdAt
) {
}