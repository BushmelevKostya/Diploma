package itmo.backend.model.dto.monitoring;

import java.util.List;

public record PageMonitoringMetricResponse(
    List<MonitoringMetricResponse> content,
    PageInfo page
) {
}