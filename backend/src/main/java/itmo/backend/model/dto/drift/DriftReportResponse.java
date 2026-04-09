package itmo.backend.model.dto.drift;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record DriftReportResponse(
    UUID id,
    UUID vmId,
    String vmName,
    DriftStatus status,
    Map<String, Object> expectedState,
    Map<String, Object> actualState,
    List<DriftDifference> differences,
    Instant checkedAt,
    Instant createdAt
) {
}