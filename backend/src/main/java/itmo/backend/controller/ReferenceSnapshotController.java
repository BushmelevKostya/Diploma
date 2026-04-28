package itmo.backend.controller;

import itmo.backend.model.dto.snapshot.SnapshotResponse;
import itmo.backend.services.SnapshotService;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/snapshots")
public class ReferenceSnapshotController {

    private final SnapshotService snapshotService;

    public ReferenceSnapshotController(final SnapshotService snapshotService) {
        this.snapshotService = snapshotService;
    }

    @GetMapping("/reference")
    public SnapshotResponse getReference() {
        return snapshotService.getReference();
    }

    @PostMapping("/{id}/reference")
    public SnapshotResponse markReference(@PathVariable final UUID id) {
        return snapshotService.markReference(id);
    }
}
