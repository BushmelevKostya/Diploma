package itmo.backend.model.dto.vm;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record UpdateVmRequest(
    @Size(max = 100, message = "hostname must be at most 100 characters")
    String hostname,

    @Min(value = 1, message = "vcpu must be at least 1")
    @Max(value = 16, message = "vcpu must be at most 16")
    Integer vcpu,

    @Min(value = 512, message = "memoryMb must be at least 512")
    @Max(value = 32768, message = "memoryMb must be at most 32768")
    Integer memoryMb
) {
}