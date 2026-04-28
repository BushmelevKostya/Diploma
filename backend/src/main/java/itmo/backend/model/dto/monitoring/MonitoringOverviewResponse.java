package itmo.backend.model.dto.monitoring;

import java.time.Instant;

public record MonitoringOverviewResponse(
  Integer runningVmCount,
  Double cpuUsagePercent,
  Integer memoryTotalMb,
  Integer memoryUsedMb,
  Double memoryUsagePercent,
  Long diskTotalBytes,
  Long diskUsedBytes,
  Double diskUsagePercent,
  Instant collectedAt,
  String source
) {
}
