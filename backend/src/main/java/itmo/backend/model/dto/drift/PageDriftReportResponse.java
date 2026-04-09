package itmo.backend.model.dto.drift;

import java.util.List;

public record PageDriftReportResponse(
    List<DriftReportResponse> content,
    PageInfo page
) {
}