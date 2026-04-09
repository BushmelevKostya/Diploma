package itmo.backend.model.dto.snapshot;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateSnapshotRequest(
    @NotBlank(message = "name must not be blank")
    @Size(max = 100, message = "name must be at most 100 characters")
    String name,

    @Size(max = 500, message = "description must be at most 500 characters")
    String description
) {
}