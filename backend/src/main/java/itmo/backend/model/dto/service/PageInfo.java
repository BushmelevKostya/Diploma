package itmo.backend.model.dto.registry;

public record PageInfo(
    int page,
    int size,
    long totalElements,
    int totalPages
) {
}