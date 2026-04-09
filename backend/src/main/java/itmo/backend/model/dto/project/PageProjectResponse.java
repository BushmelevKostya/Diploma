package itmo.backend.model.dto.project;

import java.util.List;

public record PageProjectResponse(
    List<ProjectResponse> content,
    PageInfo page
) {
}