package itmo.backend.model.dto.project;

public record PageInfo(
    int page,
    int size,
    long totalElements,
    int totalPages
) {
}