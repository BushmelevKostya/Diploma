package itmo.backend.model.dto.project;

import itmo.backend.model.dto.vm.VmResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ProjectResponse(
    UUID id,
    String name,
    String description,
    UUID ownerId,
    List<VmResponse> vms,
    Instant createdAt,
    Instant updatedAt
) {
}