package itmo.backend.model.dto.project;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record AttachVmRequest(
    @NotNull(message = "vmId must not be null")
    UUID vmId
) {
}