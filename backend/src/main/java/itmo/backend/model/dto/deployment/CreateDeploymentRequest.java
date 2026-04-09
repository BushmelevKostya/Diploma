package itmo.backend.model.dto.deployment;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CreateDeploymentRequest(
    @NotBlank(message = "name must not be blank")
    @Size(max = 100, message = "name must be at most 100 characters")
    String name,

    String version,

    @NotBlank(message = "image must not be blank")
    String image,

    @Min(value = 1, message = "replicas must be at least 1")
    Integer replicas,

    @Min(value = 1, message = "port must be at least 1")
    @Max(value = 65535, message = "port must be at most 65535")
    Integer port,

    @NotNull(message = "vmId must not be null")
    UUID vmId
) {
}