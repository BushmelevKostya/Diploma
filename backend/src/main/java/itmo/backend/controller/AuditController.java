package itmo.backend.controller;

import itmo.backend.model.dto.audit.AuditAction;
import itmo.backend.model.dto.audit.PageAuditLogResponse;
import itmo.backend.services.AuditService;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audit-logs")
public class AuditController {

    private final AuditService auditService;

    public AuditController(final AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping
    public PageAuditLogResponse list(
        @RequestParam(defaultValue = "0") final int page,
        @RequestParam(defaultValue = "20") final int size,
        @RequestParam(required = false) final UUID userId,
        @RequestParam(required = false) final AuditAction action,
        @RequestParam(required = false) final String entityType,
        @RequestParam(required = false) final Instant from,
        @RequestParam(required = false) final Instant to
    ) {
        return auditService.list(PageRequest.of(page, size), userId, action, entityType, from, to);
    }
}