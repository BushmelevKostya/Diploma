package itmo.backend.model.dto.deployment;

import java.util.List;

public record PageDeploymentResponse(
    List<DeploymentResponse> content,
    PageInfo page
) {
}