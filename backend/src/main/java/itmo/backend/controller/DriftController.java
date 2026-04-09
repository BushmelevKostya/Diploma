package itmo.backend.controller;

import itmo.backend.model.dto.drift.DriftReportResponse;
import itmo.backend.model.dto.drift.DriftStatus;
import itmo.backend.model.dto.drift.PageDriftReportResponse;
import itmo.backend.services.DriftService;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class DriftController {

    private final DriftService driftService;

    public DriftController(final DriftService driftService) {
        this.driftService = driftService;
    }

    @PostMapping("/vms/{vmId}/drift")
    public DriftReportResponse createReport(@PathVariable final UUID vmId) {
        return driftService.createReport(vmId);
    }

    @GetMapping("/drift-reports")
    public PageDriftReportResponse list(
        @RequestParam(defaultValue = "0") final int page,
        @RequestParam(defaultValue = "20") final int size,
        @RequestParam(required = false) final DriftStatus status,
        @RequestParam(required = false) final UUID vmId
    ) {
        return driftService.list(PageRequest.of(page, size), status, vmId);
    }

    @GetMapping("/drift-reports/{id}")
    public DriftReportResponse getById(@PathVariable final UUID id) {
        return driftService.getById(id);
    }
}