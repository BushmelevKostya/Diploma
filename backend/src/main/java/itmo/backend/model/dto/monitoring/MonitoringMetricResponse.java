package itmo.backend.model.dto.monitoring;

import itmo.backend.model.dto.vm.MetricType;
import java.time.Instant;
import java.util.UUID;

public record MonitoringMetricResponse(
    UUID id,
    UUID vmId,
    String vmName,
    MetricType metricType,
    Double value,
    String unit,
    Instant collectedAt
) {
}