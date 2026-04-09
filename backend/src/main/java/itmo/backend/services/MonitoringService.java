package itmo.backend.services;

import itmo.backend.model.dto.monitoring.MonitoringHealthResponse;
import itmo.backend.model.dto.monitoring.MonitoringMetricResponse;
import itmo.backend.model.dto.monitoring.PageInfo;
import itmo.backend.model.dto.monitoring.PageMonitoringMetricResponse;
import itmo.backend.model.dto.registry.ServiceType;
import itmo.backend.model.dto.vm.MetricType;
import itmo.backend.model.entity.VirtualMachine;
import itmo.backend.model.repository.VirtualMachineRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
public class MonitoringService {

    private final VirtualMachineRepository virtualMachineRepository;

    public MonitoringService(final VirtualMachineRepository virtualMachineRepository) {
        this.virtualMachineRepository = virtualMachineRepository;
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
}