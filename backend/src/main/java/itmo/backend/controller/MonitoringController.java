package itmo.backend.controller;

import itmo.backend.model.dto.monitoring.MonitoringHealthResponse;
import itmo.backend.model.dto.monitoring.PageMonitoringMetricResponse;
import itmo.backend.model.dto.vm.MetricType;
import itmo.backend.services.MonitoringService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/monitoring")
public class MonitoringController {

    private final MonitoringService monitoringService;

    public MonitoringController(final MonitoringService monitoringService) {
        this.monitoringService = monitoringService;
    }

    @GetMapping("/metrics")
    public PageMonitoringMetricResponse metrics(
        @RequestParam(defaultValue = "0") final int page,
        @RequestParam(defaultValue = "20") final int size,
        @RequestParam(required = false) final UUID vmId,
        @RequestParam(required = false) final MetricType type,
        @RequestParam(required = false) final Instant from,
        @RequestParam(required = false) final Instant to
    ) {
        return monitoringService.metrics(PageRequest.of(page, size), vmId, type, from, to);
    }

    @GetMapping("/health")
    public List<MonitoringHealthResponse> health() {
        return monitoringService.health();
    }
}