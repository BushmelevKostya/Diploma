package itmo.backend.services;

import itmo.backend.model.dto.monitoring.MonitoringHealthResponse;
import itmo.backend.model.dto.monitoring.MonitoringMetricResponse;
import itmo.backend.model.dto.monitoring.MonitoringOverviewResponse;
import itmo.backend.model.dto.monitoring.PageInfo;
import itmo.backend.model.dto.monitoring.PageMonitoringMetricResponse;
import itmo.backend.model.dto.registry.ServiceType;
import itmo.backend.model.dto.vm.MetricType;
import itmo.backend.model.entity.VirtualMachine;
import itmo.backend.model.entity.VmStatus;
import itmo.backend.model.repository.VirtualMachineRepository;
import java.io.IOException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
public class MonitoringService {

  private static final Logger log = LoggerFactory.getLogger(MonitoringService.class);

  private final VirtualMachineRepository virtualMachineRepository;
  private final InfrastructureCommandService infrastructureCommandService;

  public MonitoringService(
    final VirtualMachineRepository virtualMachineRepository,
    final InfrastructureCommandService infrastructureCommandService
  ) {
    this.virtualMachineRepository = virtualMachineRepository;
    this.infrastructureCommandService = infrastructureCommandService;
  }

  public MonitoringOverviewResponse overview() {
    if (!infrastructureCommandService.isEnabled()) {
      return fallbackOverview();
    }

    try {
      final String output = infrastructureCommandService.runOnVirtualizationHostAndCapture(
        "monitoring-overview",
        """
        export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
        set -eu

        running=$(virsh list --state-running --name 2>/dev/null | grep -cE '^[a-zA-Z0-9]' || true)
        if [ -z "$running" ]; then running=0; fi

        line1=$(awk '/^cpu / {print $2" "$3" "$4" "$5" "$6" "$7" "$8" "$9; exit}' /proc/stat)
        sleep 1
        line2=$(awk '/^cpu / {print $2" "$3" "$4" "$5" "$6" "$7" "$8" "$9; exit}' /proc/stat)

        set -- $line1
        u1=$1; n1=$2; s1=$3; i1=$4; w1=$5; q1=$6; sq1=$7; st1=$8
        set -- $line2
        u2=$1; n2=$2; s2=$3; i2=$4; w2=$5; q2=$6; sq2=$7; st2=$8

        total1=$((u1 + n1 + s1 + i1 + w1 + q1 + sq1 + st1))
        total2=$((u2 + n2 + s2 + i2 + w2 + q2 + sq2 + st2))
        idle1=$((i1 + w1))
        idle2=$((i2 + w2))

        total_diff=$((total2 - total1))
        idle_diff=$((idle2 - idle1))

        if [ "$total_diff" -gt 0 ]; then
            cpu_usage=$(awk -v t="$total_diff" -v i="$idle_diff" 'BEGIN { printf "%.2f", (t - i) * 100.0 / t }')
        else
            cpu_usage="0"
        fi

        mem_total_kb=$(awk '/^MemTotal:/ {print $2}' /proc/meminfo)
        mem_avail_kb=$(awk '/^MemAvailable:/ {print $2}' /proc/meminfo)
        : "${mem_total_kb:=0}"
        : "${mem_avail_kb:=0}"

        disk_line=$(df -PB1 / | awk 'NR==2 {print $2" "$3}')
        disk_total=$(echo "$disk_line" | awk '{print $1}')
        disk_used=$(echo "$disk_line" | awk '{print $2}')
        : "${disk_total:=0}"
        : "${disk_used:=0}"

        echo "$running $cpu_usage $mem_total_kb $mem_avail_kb $disk_total $disk_used"
        """
      );

      log.info("monitoring-overview raw output: [{}]", output == null ? "" : output.replace('\n', '|').trim());

      if (output == null || output.isBlank()) {
        return fallbackOverview();
      }

      final String trimmed = output.trim();
      final String[] parts = trimmed.split("\\s+");
      if (parts.length < 6) {
        log.warn("monitoring-overview unexpected parts count: {}", parts.length);
        return fallbackOverview();
      }

      final int runningVmCount = parseInteger(parts[0]);
      final double cpuUsagePercent = clampPercent(parseDouble(parts[1]));
      final long memTotalKb = parseLong(parts[2]);
      final long memAvailKb = parseLong(parts[3]);
      final long diskTotalBytes = parseLong(parts[4]);
      final long diskUsedBytes = parseLong(parts[5]);

      final int memoryTotalMb = (int) Math.max(0L, memTotalKb / 1024L);
      final int memoryAvailableMb = (int) Math.max(0L, memAvailKb / 1024L);
      final int memoryUsedMb = Math.max(0, memoryTotalMb - memoryAvailableMb);
      final double memoryUsagePercent = memoryTotalMb > 0
        ? clampPercent((memoryUsedMb * 100.0) / memoryTotalMb)
        : 0.0;

      final double diskUsagePercent = diskTotalBytes > 0
        ? clampPercent((diskUsedBytes * 100.0) / diskTotalBytes)
        : 0.0;

      return new MonitoringOverviewResponse(
        runningVmCount,
        cpuUsagePercent,
        memoryTotalMb,
        memoryUsedMb,
        memoryUsagePercent,
        Math.max(0L, diskTotalBytes),
        Math.max(0L, diskUsedBytes),
        diskUsagePercent,
        Instant.now(),
        "virtualization-host"
      );
    } catch (final IOException | InterruptedException exception) {
      log.warn("monitoring-overview failed: {}", exception.getMessage());
      return fallbackOverview();
    }
  }

  public PageMonitoringMetricResponse metrics(
    final Pageable pageable,
    final UUID vmId,
    final MetricType type,
    final Instant from,
    final Instant to
  ) {
    final List<MonitoringMetricResponse> filtered = virtualMachineRepository.findAll().stream()
      .filter(vm -> vmId == null || vm.getId().equals(vmId))
      .flatMap(vm -> metricsForVm(vm).stream())
      .filter(metric -> type == null || metric.metricType() == type)
      .filter(metric -> from == null || !metric.collectedAt().isBefore(from))
      .filter(metric -> to == null || !metric.collectedAt().isAfter(to))
      .sorted(Comparator.comparing(MonitoringMetricResponse::collectedAt).reversed())
      .toList();

    final int page = pageable.getPageNumber();
    final int size = pageable.getPageSize();
    final int fromIndex = Math.min(page * size, filtered.size());
    final int toIndex = Math.min(fromIndex + size, filtered.size());

    return new PageMonitoringMetricResponse(
      filtered.subList(fromIndex, toIndex),
      new PageInfo(page, size, filtered.size(), size == 0 ? 0 : (int) Math.ceil((double) filtered.size() / size))
    );
  }

  public List<MonitoringHealthResponse> health() {
    return List.of(
      new MonitoringHealthResponse("prometheus", ServiceType.MONITORING, "UP", "http://prometheus.local", 42, Instant.now()),
      new MonitoringHealthResponse("postgres", ServiceType.DATABASE, "UP", "jdbc:postgresql://localhost:5432/diploma", 18, Instant.now()),
      new MonitoringHealthResponse("backend", ServiceType.APPLICATION, "UP", "http://localhost:8080", 7, Instant.now())
    );
  }

  private List<MonitoringMetricResponse> metricsForVm(final VirtualMachine vm) {
    return List.of(
      new MonitoringMetricResponse(UUID.randomUUID(), vm.getId(), vm.getName(), MetricType.CPU, cpuValue(vm), "%", Instant.now()),
      new MonitoringMetricResponse(UUID.randomUUID(), vm.getId(), vm.getName(), MetricType.MEMORY, memoryValue(vm), "MB", Instant.now()),
      new MonitoringMetricResponse(UUID.randomUUID(), vm.getId(), vm.getName(), MetricType.DISK, diskValue(vm), "GB", Instant.now()),
      new MonitoringMetricResponse(UUID.randomUUID(), vm.getId(), vm.getName(), MetricType.NETWORK, networkValue(vm), "Mbps", Instant.now())
    );
  }

  private Double cpuValue(final VirtualMachine vm) {
    return vm.getStatus() == null || vm.getStatus().name().equals("STOPPED") ? 0.0 : Math.min(100.0, vm.getVcpu() * 12.5);
  }

  private Double memoryValue(final VirtualMachine vm) {
    return vm.getStatus() == null || vm.getStatus().name().equals("STOPPED") ? 0.0 : vm.getMemoryMb().doubleValue();
  }

  private Double diskValue(final VirtualMachine vm) {
    return vm.getDiskSizeGb().doubleValue();
  }

  private Double networkValue(final VirtualMachine vm) {
    return vm.getStatus() != null && vm.getStatus().name().equals("RUNNING") ? 12.0 : 0.0;
  }

  private MonitoringOverviewResponse fallbackOverview() {
    final List<VirtualMachine> all = virtualMachineRepository.findAll();
    final int runningVmCount = (int) all.stream()
      .filter(vm -> vm.getStatus() == VmStatus.RUNNING)
      .count();
    final int memoryTotalMb = all.stream()
      .map(VirtualMachine::getMemoryMb)
      .mapToInt(Integer::intValue)
      .sum();

    return new MonitoringOverviewResponse(
      runningVmCount,
      0.0,
      memoryTotalMb,
      0,
      0.0,
      0L,
      0L,
      0.0,
      Instant.now(),
      "fallback"
    );
  }

  private Integer parseInteger(final String value) {
    try {
      return Integer.valueOf(value.trim());
    } catch (final Exception ignored) {
      return 0;
    }
  }

  private Long parseLong(final String value) {
    try {
      return Long.valueOf(value.trim());
    } catch (final Exception ignored) {
      return 0L;
    }
  }

  private Double parseDouble(final String value) {
    try {
      return Double.valueOf(value.trim().replace(',', '.'));
    } catch (final Exception ignored) {
      return 0.0;
    }
  }

  private Double clampPercent(final double value) {
    if (Double.isNaN(value) || Double.isInfinite(value)) {
      return 0.0;
    }
    return Math.max(0.0, Math.min(100.0, value));
  }
}
