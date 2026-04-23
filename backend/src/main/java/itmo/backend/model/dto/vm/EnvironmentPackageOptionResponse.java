package itmo.backend.model.dto.vm;

public record EnvironmentPackageOptionResponse(
    EnvironmentPackage code,
    String title,
    String description
) {
}
