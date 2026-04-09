package itmo.backend.services;

import itmo.backend.model.dto.snapshot.CreateSnapshotRequest;
import itmo.backend.model.dto.snapshot.SnapshotResponse;
import itmo.backend.model.dto.snapshot.SnapshotStatus;
import itmo.backend.model.dto.vm.VmResponse;
import itmo.backend.model.entity.VirtualMachine;
import itmo.backend.model.exceptions.ApiException;
import itmo.backend.model.repository.VirtualMachineRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class SnapshotService {

    private final VirtualMachineRepository virtualMachineRepository;
    private final Map<UUID, Map<UUID, SnapshotRecord>> snapshotsByVmId = new ConcurrentHashMap<>();

    public SnapshotService(final VirtualMachineRepository virtualMachineRepository) {
        this.virtualMachineRepository = virtualMachineRepository;
    }

    public SnapshotResponse create(final UUID vmId, final CreateSnapshotRequest request) {
        final VirtualMachine vm = findVm(vmId);
        final SnapshotRecord record = new SnapshotRecord(
            UUID.randomUUID(),
            request.name(),
            request.description(),
            SnapshotStatus.READY,
            vmId,
            snapshotSizeBytes(vm),
            Instant.now()
        );

        snapshotsByVmId.computeIfAbsent(vmId, ignored -> new ConcurrentHashMap<>())
            .put(record.id(), record);

        return toResponse(record);
    }

    public List<SnapshotResponse> list(final UUID vmId) {
        findVm(vmId);
        return snapshotsByVmId.getOrDefault(vmId, Map.of()).values().stream()
            .sorted(Comparator.comparing(SnapshotRecord::createdAt).reversed())
            .map(this::toResponse)
            .toList();
    }

    public SnapshotResponse getById(final UUID vmId, final UUID id) {
        return toResponse(findSnapshot(vmId, id));
    }

    public void delete(final UUID vmId, final UUID id) {
        findSnapshot(vmId, id);
        final Map<UUID, SnapshotRecord> snapshots = snapshotsByVmId.get(vmId);
        if (snapshots != null) {
            snapshots.remove(id);
        }
    }

    public VmResponse restore(final UUID vmId, final UUID id, final VirtualMachineService virtualMachineService) {
        findSnapshot(vmId, id);
        final VirtualMachine vm = findVm(vmId);
        vm.stop();
        virtualMachineRepository.save(vm);
        return virtualMachineService.getById(vmId);
    }

    private VirtualMachine findVm(final UUID vmId) {
        return virtualMachineRepository.findById(vmId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "VM not found"));
    }

    private SnapshotRecord findSnapshot(final UUID vmId, final UUID id) {
        final SnapshotRecord record = snapshotsByVmId.getOrDefault(vmId, Map.of()).get(id);
        if (record == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Snapshot not found");
        }

        return record;
    }

    private SnapshotResponse toResponse(final SnapshotRecord record) {
        return new SnapshotResponse(
            record.id(),
            record.name(),
            record.description(),
            record.status(),
            record.vmId(),
            record.sizeBytes(),
            record.createdAt()
        );
    }

    private long snapshotSizeBytes(final VirtualMachine vm) {
        return vm.getDiskSizeGb().longValue() * 1024L * 1024L * 1024L;
    }

    private record SnapshotRecord(
        UUID id,
        String name,
        String description,
        SnapshotStatus status,
        UUID vmId,
        long sizeBytes,
        Instant createdAt
    ) {
    }
}