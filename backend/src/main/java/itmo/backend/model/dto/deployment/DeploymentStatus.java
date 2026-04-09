package itmo.backend.model.dto.deployment;

public enum DeploymentStatus {
    PENDING,
    DEPLOYING,
    ACTIVE,
    FAILED,
    ROLLED_BACK
}