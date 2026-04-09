package itmo.backend.model.dto.registry;

import java.util.List;

public record PageServiceResponse(
    List<ServiceResponse> content,
    PageInfo page
) {
}