package itmo.backend.model.dto.audit;

public enum AuditAction {
    CREATE,
    UPDATE,
    DELETE,
    START,
    STOP,
    DEPLOY,
    SNAPSHOT,
    ROLLBACK
}