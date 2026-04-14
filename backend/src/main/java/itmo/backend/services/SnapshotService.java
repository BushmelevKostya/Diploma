package itmo.backend.services;

import itmo.backend.model.dto.snapshot.CreateSnapshotRequest;
import itmo.backend.model.dto.snapshot.SnapshotResponse;
import itmo.backend.model.dto.snapshot.SnapshotStatus;
import itmo.backend.model.dto.vm.VmResponse;
import itmo.backend.model.entity.VirtualMachine;
import itmo.backend.model.entity.VirtualMachineSnapshot;
import itmo.backend.model.exceptions.ApiException;
import itmo.backend.model.repository.VirtualMachineSnapshotRepository;
import itmo.backend.model.repository.VirtualMachineRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class SnapshotService {

    private final VirtualMachineRepository virtualMachineRepository;
    private final VirtualMachineSnapshotRepository virtualMachineSnapshotRepository;

    public SnapshotService(
        final VirtualMachineRepository virtualMachineRepository,
        final VirtualMachineSnapshotRepository virtualMachineSnapshotRepository
    ) {
        this.virtualMachineRepository = virtualMachineRepository;
        this.virtualMachineSnapshotRepository = virtualMachineSnapshotRepository;
    }

    public SnapshotResponse create(final UUID vmId, final CreateSnapshotRequest request) {
        final VirtualMachine vm = findVm(vmId);
        final VirtualMachineSnapshot record = new VirtualMachineSnapshot(
            request.name(),
            request.description(),
            SnapshotStatus.READY,
            vmId,
            snapshotSizeBytes(vm)
        );

        final VirtualMachineSnapshot saved = virtualMachineSnapshotRepository.save(record);
        return toResponse(saved);
    }

    public List<SnapshotResponse> list(final UUID vmId) {
        findVm(vmId);
        return virtualMachineSnapshotRepository.findAllByVmIdOrderByCreatedAtDesc(vmId).stream()
            .map(this::toResponse)
            .toList();
    }

    public SnapshotResponse getById(final UUID vmId, final UUID id) {
        return toResponse(findSnapshot(vmId, id));
    }

    public void delete(final UUID vmId, final UUID id) {
        final VirtualMachineSnapshot snapshot = findSnapshot(vmId, id);
        virtualMachineSnapshotRepository.delete(snapshot);
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

    private VirtualMachineSnapshot findSnapshot(final UUID vmId, final UUID id) {
        final VirtualMachineSnapshot record = virtualMachineSnapshotRepository.findByIdAndVmId(id, vmId)
            .orElse(null);
        if (record == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Snapshot not found");
        }

        return record;
    }

    private SnapshotResponse toResponse(final VirtualMachineSnapshot record) {
        return new SnapshotResponse(
            record.getId(),
            record.getName(),
            record.getDescription(),
            record.getStatus(),
            record.getVmId(),
            record.getSizeBytes(),
            record.getCreatedAt()
        );
    }

    private long snapshotSizeBytes(final VirtualMachine vm) {
        return vm.getDiskSizeGb().longValue() * 1024L * 1024L * 1024L;
    }
}