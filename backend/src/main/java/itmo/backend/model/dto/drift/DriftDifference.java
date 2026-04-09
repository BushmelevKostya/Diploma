package itmo.backend.model.dto.drift;

public record DriftDifference(
    String field,
    String expected,
    String actual
) {
}