package itmo.backend.model.dto.vm;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateVmRequest(
    @NotBlank(message = "name must not be blank")
    @Size(max = 100, message = "name must be at most 100 characters")
    String name,

    @Min(value = 1, message = "cpuCores must be at least 1")
    @Max(value = 64, message = "cpuCores must be at most 64")
    Integer cpuCores,

    @Min(value = 512, message = "memoryMb must be at least 512")
    @Max(value = 1048576, message = "memoryMb must be at most 1048576")
    Integer memoryMb,

    @Min(value = 5, message = "diskGb must be at least 5")
    @Max(value = 4096, message = "diskGb must be at most 4096")
    Integer diskGb
) {
}
