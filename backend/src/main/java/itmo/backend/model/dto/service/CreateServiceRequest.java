package itmo.backend.model.dto.registry;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CreateServiceRequest(
    @NotBlank(message = "name must not be blank")
    @Size(max = 100, message = "name must be at most 100 characters")
    String name,

    @Size(max = 500, message = "description must be at most 500 characters")
    String description,

    @NotNull(message = "serviceType must not be null")
    ServiceType serviceType,

    String url,

    @NotNull(message = "vmId must not be null")
    UUID vmId
) {
}