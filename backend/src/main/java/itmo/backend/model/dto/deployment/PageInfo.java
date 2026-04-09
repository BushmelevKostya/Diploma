package itmo.backend.model.dto.deployment;

public record PageInfo(
    int page,
    int size,
    long totalElements,
    int totalPages
) {
}