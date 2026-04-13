package itmo.backend.model.dto.vm;

public record PageInfo(
    int page,
    int size,
    long totalElements,
    int totalPages
) {
}
