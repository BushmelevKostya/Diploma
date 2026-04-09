package itmo.backend.model.dto.project;

import jakarta.validation.constraints.Size;

public record UpdateProjectRequest(
    @Size(max = 100, message = "name must be at most 100 characters")
    String name,

    @Size(max = 500, message = "description must be at most 500 characters")
    String description
) {
}