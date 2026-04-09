package itmo.backend.controller;

import itmo.backend.model.dto.snapshot.CreateSnapshotRequest;
import itmo.backend.model.dto.snapshot.SnapshotResponse;
import itmo.backend.services.SnapshotService;
import itmo.backend.services.VirtualMachineService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/vms/{vmId}/snapshots")
public class SnapshotController {

    private final SnapshotService snapshotService;
    private final VirtualMachineService virtualMachineService;

    public SnapshotController(
        final SnapshotService snapshotService,
        final VirtualMachineService virtualMachineService
    ) {
        this.snapshotService = snapshotService;
        this.virtualMachineService = virtualMachineService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SnapshotResponse create(
        @PathVariable final UUID vmId,
        @Valid @RequestBody final CreateSnapshotRequest request
    ) {
        return snapshotService.create(vmId, request);
    }

    @GetMapping
    public List<SnapshotResponse> list(@PathVariable final UUID vmId) {
        return snapshotService.list(vmId);
    }

    @GetMapping("/{id}")
    public SnapshotResponse getById(
        @PathVariable final UUID vmId,
        @PathVariable final UUID id
    ) {
        return snapshotService.getById(vmId, id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
        @PathVariable final UUID vmId,
        @PathVariable final UUID id
    ) {
        snapshotService.delete(vmId, id);
    }

    @PostMapping("/{id}/restore")
    public itmo.backend.model.dto.vm.VmResponse restore(
        @PathVariable final UUID vmId,
        @PathVariable final UUID id
    ) {
        return snapshotService.restore(vmId, id, virtualMachineService);
    }
}