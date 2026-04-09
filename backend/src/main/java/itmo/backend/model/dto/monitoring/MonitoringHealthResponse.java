package itmo.backend.model.dto.monitoring;

import itmo.backend.model.dto.registry.ServiceType;

public record MonitoringHealthResponse(
    String serviceName,
    ServiceType serviceType,
    String status,
    String url,
    Integer responseTimeMs,
    java.time.Instant checkedAt
) {
}