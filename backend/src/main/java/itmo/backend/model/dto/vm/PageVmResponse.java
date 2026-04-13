package itmo.backend.model.dto.vm;

import java.util.List;

public record PageVmResponse(
    List<VmResponse> content,
    PageInfo page
) {
}
