package itmo.backend.model.dto.drift;

public record PageInfo(
    int page,
    int size,
    long totalElements,
    int totalPages
) {
}