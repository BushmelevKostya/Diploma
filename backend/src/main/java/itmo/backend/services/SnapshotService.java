package itmo.backend.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import itmo.backend.model.dto.snapshot.CreateSnapshotRequest;
import itmo.backend.model.dto.snapshot.SnapshotResponse;
import itmo.backend.model.dto.snapshot.SnapshotStatus;
import itmo.backend.model.dto.vm.VmResponse;
import itmo.backend.model.entity.VirtualMachine;
import itmo.backend.model.entity.VirtualMachineSnapshot;
import itmo.backend.model.entity.VmStatus;
import itmo.backend.model.exceptions.ApiException;
import itmo.backend.model.repository.VirtualMachineSnapshotRepository;
import itmo.backend.model.repository.VirtualMachineRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class SnapshotService {
    private static final Logger log = LoggerFactory.getLogger(SnapshotService.class);

    private final VirtualMachineRepository virtualMachineRepository;
    private final VirtualMachineSnapshotRepository virtualMachineSnapshotRepository;
    private final InfrastructureCommandService infrastructureCommandService;
    private final VmStateProfileService vmStateProfileService;
    private final ObjectMapper objectMapper;

    public SnapshotService(
        final VirtualMachineRepository virtualMachineRepository,
        final VirtualMachineSnapshotRepository virtualMachineSnapshotRepository,
        final InfrastructureCommandService infrastructureCommandService,
        final VmStateProfileService vmStateProfileService,
        final ObjectMapper objectMapper
    ) {
        this.virtualMachineRepository = virtualMachineRepository;
        this.virtualMachineSnapshotRepository = virtualMachineSnapshotRepository;
        this.infrastructureCommandService = infrastructureCommandService;
        this.vmStateProfileService = vmStateProfileService;
        this.objectMapper = objectMapper;
    }

    public SnapshotResponse create(final UUID vmId, final CreateSnapshotRequest request) {
        if (!infrastructureCommandService.isEnabled()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Infrastructure commands are disabled");
        }

        final VirtualMachine vm = findVm(vmId);
        final boolean wasRunning;
        try {
            wasRunning = resolveActualVmStatus(vm.getName()) == VmStatus.RUNNING;
        } catch (final Exception exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Snapshot creation failed: " + rootMessage(exception));
        }

        final String libvirtSnapshotName = generateLibvirtSnapshotName(vm.getName());
        final VirtualMachineSnapshot record = new VirtualMachineSnapshot(
            request.name(),
            request.description(),
            SnapshotStatus.CREATING,
            vmId,
            snapshotSizeBytes(vm),
            libvirtSnapshotName,
            true,
            true,
            false,
            false,
            null
        );

        final VirtualMachineSnapshot saved = virtualMachineSnapshotRepository.save(record);
        try {
            captureSnapshotProfile(vm, saved);
            infrastructureCommandService.createExternalDiskSnapshot(vm.getName(), libvirtSnapshotName);

            if (wasRunning) {
                try {
                    infrastructureCommandService.startVm(vm.getName());
                } catch (final Exception restartException) {
                    log.warn("VM {} was not restarted automatically after snapshot {}", vm.getName(), libvirtSnapshotName, restartException);
                }
            }

            try {
                final VmStatus actualStatus = resolveActualVmStatus(vm.getName());
                if (actualStatus == VmStatus.RUNNING) {
                    vm.start();
                } else if (actualStatus == VmStatus.STOPPED) {
                    vm.stop();
                } else {
                    vm.markError("VM state after snapshot creation is unknown");
                }
                virtualMachineRepository.save(vm);
            } catch (final Exception statusException) {
                log.warn("Unable to resync VM {} state after snapshot creation", vm.getName(), statusException);
            }

            saved.markReady();
            return toResponse(virtualMachineSnapshotRepository.save(saved));
        } catch (final Exception exception) {
            log.error("Failed to create external snapshot {} for VM {}", libvirtSnapshotName, vm.getName(), exception);
            saved.markFailed();
            virtualMachineSnapshotRepository.save(saved);
            throw new ApiException(HttpStatus.BAD_REQUEST, "Snapshot creation failed: " + rootMessage(exception));
        }
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
        if (!infrastructureCommandService.isEnabled()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Infrastructure commands are disabled");
        }

        final VirtualMachine vm = findVm(vmId);
        final VirtualMachineSnapshot snapshot = findSnapshot(vmId, id);
        try {
            infrastructureCommandService.deleteSnapshotMetadata(vm.getName(), snapshot.getLibvirtSnapshotName());
        } catch (final Exception exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Snapshot delete failed: " + rootMessage(exception));
        }
        virtualMachineSnapshotRepository.delete(snapshot);
    }

    public VmResponse restore(final UUID vmId, final UUID id, final VirtualMachineService virtualMachineService) {
        if (!infrastructureCommandService.isEnabled()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Infrastructure commands are disabled");
        }

        final VirtualMachineSnapshot snapshot = findSnapshot(vmId, id);
        final VirtualMachine vm = findVm(vmId);
        final boolean wasRunning;
        try {
            wasRunning = resolveActualVmStatus(vm.getName()) == VmStatus.RUNNING;
        } catch (final Exception exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Snapshot restore failed: " + rootMessage(exception));
        }
        snapshot.markRestoring();
        virtualMachineSnapshotRepository.save(snapshot);

        try {
            if (wasRunning) {
                infrastructureCommandService.stopVm(vm.getName());
            }
            infrastructureCommandService.restoreExternalDiskSnapshot(vm.getName(), snapshot.getLibvirtSnapshotName());
            if (wasRunning) {
                infrastructureCommandService.startVm(vm.getName());
            }
            final VmStatus actualStatus = resolveActualVmStatus(vm.getName());
            if (actualStatus == VmStatus.RUNNING) {
                vm.start();
            } else if (actualStatus == VmStatus.STOPPED) {
                vm.stop();
            } else {
                vm.markError("VM state after snapshot restore is unknown");
            }
            virtualMachineRepository.save(vm);

            snapshot.markReady();
            virtualMachineSnapshotRepository.save(snapshot);
            return virtualMachineService.getById(vmId);
        } catch (final Exception exception) {
            log.error("Failed to restore VM {} from snapshot {}", vm.getName(), snapshot.getLibvirtSnapshotName(), exception);
            snapshot.markFailed();
            virtualMachineSnapshotRepository.save(snapshot);
            throw new ApiException(HttpStatus.BAD_REQUEST, "Snapshot restore failed: " + rootMessage(exception));
        }
    }

    public SnapshotResponse markReference(final UUID snapshotId) {
        final VirtualMachineSnapshot snapshot = virtualMachineSnapshotRepository.findById(snapshotId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Snapshot not found"));

        if (!Boolean.TRUE.equals(snapshot.getProfileCaptured())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Snapshot has no captured system profile and cannot be used as reference");
        }

        for (final VirtualMachineSnapshot referenceSnapshot : virtualMachineSnapshotRepository.findAllByReferenceSnapshotTrue()) {
            referenceSnapshot.markReference(false);
            virtualMachineSnapshotRepository.save(referenceSnapshot);
        }

        snapshot.markReference(true);
        return toResponse(virtualMachineSnapshotRepository.save(snapshot));
    }

    public SnapshotResponse getReference() {
        final VirtualMachineSnapshot snapshot = virtualMachineSnapshotRepository.findFirstByReferenceSnapshotTrue()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Reference snapshot not set"));
        return toResponse(snapshot);
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
            record.getReferenceSnapshot(),
            record.getProfileCaptured(),
            record.getCreatedAt()
        );
    }

    private void captureSnapshotProfile(final VirtualMachine vm, final VirtualMachineSnapshot snapshot) {
        try {
            final Map<String, Object> profile = vmStateProfileService.captureProfile(vm);
            snapshot.updateSystemProfile(objectMapper.writeValueAsString(profile), true);
            virtualMachineSnapshotRepository.save(snapshot);
        } catch (final Exception exception) {
            log.warn("Failed to capture system profile for snapshot {} of VM {}: {}",
                snapshot.getLibvirtSnapshotName(), vm.getName(), rootMessage(exception));
            snapshot.updateSystemProfile(null, false);
            virtualMachineSnapshotRepository.save(snapshot);
        }
    }

    private long snapshotSizeBytes(final VirtualMachine vm) {
        return vm.getDiskSizeGb().longValue() * 1024L * 1024L * 1024L;
    }

    private String generateLibvirtSnapshotName(final String vmName) {
        final String normalizedVmName = vmName == null
            ? "vm"
            : vmName.replaceAll("[^a-zA-Z0-9_-]", "-");
        final String shortId = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        return "ext-" + normalizedVmName + "-" + shortId;
    }

    private VmStatus resolveActualVmStatus(final String vmName) throws Exception {
        final String domState = infrastructureCommandService.resolveVmPowerState(vmName);
        final String normalized = domState == null ? "" : domState.trim().toLowerCase();
        if (normalized.startsWith("running")) {
            return VmStatus.RUNNING;
        }
        if (normalized.startsWith("shut off") || normalized.startsWith("shutdown") || normalized.startsWith("stopped")) {
            return VmStatus.STOPPED;
        }
        return VmStatus.ERROR;
    }

    private String rootMessage(final Exception exception) {
        Throwable current = exception;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        final String message = current.getMessage();
        if (message == null || message.isBlank()) {
            return "unknown error";
        }
        return message.replace('\r', ' ').replace('\n', ' ').trim();
    }
}
