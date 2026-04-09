package itmo.backend.model.dto.audit;

public record PageInfo(
    int page,
    int size,
    long totalElements,
    int totalPages
) {
}