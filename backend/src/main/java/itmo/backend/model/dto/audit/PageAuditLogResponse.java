package itmo.backend.model.dto.audit;

import java.util.List;

public record PageAuditLogResponse(
    List<AuditLogResponse> content,
    PageInfo page
) {
}