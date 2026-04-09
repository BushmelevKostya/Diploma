package itmo.backend.model.dto.monitoring;

public record PageInfo(
    int page,
    int size,
    long totalElements,
    int totalPages
) {
}