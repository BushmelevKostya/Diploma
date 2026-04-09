package itmo.backend.services;

import itmo.backend.model.dto.audit.AuditAction;
import itmo.backend.model.dto.audit.AuditLogResponse;
import itmo.backend.model.dto.audit.PageAuditLogResponse;
import itmo.backend.model.dto.audit.PageInfo;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
public class AuditService {

    private final List<AuditLogRecord> records = new CopyOnWriteArrayList<>();

    public AuditService() {
        records.add(new AuditLogRecord(UUID.randomUUID(), UUID.randomUUID(), "admin", AuditAction.CREATE, "VirtualMachine", UUID.randomUUID(), Map.of("name", "vm1"), Instant.now()));
        records.add(new AuditLogRecord(UUID.randomUUID(), UUID.randomUUID(), "operator", AuditAction.UPDATE, "Project", UUID.randomUUID(), Map.of("name", "project-a"), Instant.now()));
    }

    public PageAuditLogResponse list(
        final Pageable pageable,
        final UUID userId,
        final AuditAction action,
        final String entityType,
        final Instant from,
        final Instant to
    ) {
        final List<AuditLogRecord> filtered = records.stream()
            .filter(record -> userId == null || record.userId().equals(userId))
            .filter(record -> action == null || record.action() == action)
            .filter(record -> entityType == null || record.entityType().equalsIgnoreCase(entityType))
            .filter(record -> from == null || !record.createdAt().isBefore(from))
            .filter(record -> to == null || !record.createdAt().isAfter(to))
            .sorted(Comparator.comparing(AuditLogRecord::createdAt).reversed())
            .toList();

        final int page = pageable.getPageNumber();
        final int size = pageable.getPageSize();
        final int fromIndex = Math.min(page * size, filtered.size());
        final int toIndex = Math.min(fromIndex + size, filtered.size());

        return new PageAuditLogResponse(
            filtered.subList(fromIndex, toIndex).stream().map(this::toResponse).toList(),
            new PageInfo(page, size, filtered.size(), size == 0 ? 0 : (int) Math.ceil((double) filtered.size() / size))
        );
    }

    private AuditLogResponse toResponse(final AuditLogRecord record) {
        return new AuditLogResponse(
            record.id(),
            record.userId(),
            record.username(),
            record.action(),
            record.entityType(),
            record.entityId(),
            record.details(),
            record.createdAt()
        );
    }

    private record AuditLogRecord(
        UUID id,
        UUID userId,
        String username,
        AuditAction action,
        String entityType,
        UUID entityId,
        Map<String, Object> details,
        Instant createdAt
    ) {
    }
}