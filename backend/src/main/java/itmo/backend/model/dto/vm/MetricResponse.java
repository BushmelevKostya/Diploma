package itmo.backend.model.dto.vm;

import java.time.Instant;
import java.util.UUID;

public record MetricResponse(
    UUID id,
    UUID vmId,
    String vmName,
    MetricType metricType,
    Double value,
    String unit,
    Instant collectedAt
) {
}