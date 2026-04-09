package itmo.backend.model.dto.registry;

public record UpdateServiceRequest(
    String description,
    String url,
    ServiceStatus status
) {
}