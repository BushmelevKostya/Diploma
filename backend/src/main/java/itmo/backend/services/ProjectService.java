package itmo.backend.services;

import itmo.backend.model.dto.project.AttachVmRequest;
import itmo.backend.model.dto.project.CreateProjectRequest;
import itmo.backend.model.dto.project.PageInfo;
import itmo.backend.model.dto.project.PageProjectResponse;
import itmo.backend.model.dto.project.ProjectResponse;
import itmo.backend.model.dto.project.UpdateProjectRequest;
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
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ProjectService {

    private final VirtualMachineRepository virtualMachineRepository;
    private final Map<UUID, ProjectRecord> projects = new ConcurrentHashMap<>();

    public ProjectService(final VirtualMachineRepository virtualMachineRepository) {
        this.virtualMachineRepository = virtualMachineRepository;
    }

    public ProjectResponse create(final CreateProjectRequest request) {
        ensureProjectNameUnique(request.name(), null);

        final ProjectRecord record = new ProjectRecord(
            UUID.randomUUID(),
            request.name(),
            request.description(),
            null,
            new ArrayList<>(),
            Instant.now(),
            Instant.now()
        );

        projects.put(record.id(), record);
        return toResponse(record);
    }

    public PageProjectResponse list(final Pageable pageable) {
        final List<ProjectRecord> filtered = projects.values().stream()
            .sorted(Comparator.comparing(ProjectRecord::createdAt).reversed())
            .toList();

        final int page = pageable.getPageNumber();
        final int size = pageable.getPageSize();
        final int fromIndex = Math.min(page * size, filtered.size());
        final int toIndex = Math.min(fromIndex + size, filtered.size());

        return new PageProjectResponse(
            filtered.subList(fromIndex, toIndex).stream().map(this::toResponse).toList(),
            new PageInfo(page, size, filtered.size(), size == 0 ? 0 : (int) Math.ceil((double) filtered.size() / size))
        );
    }

    public ProjectResponse getById(final UUID id) {
        return toResponse(findProject(id));
    }

    public ProjectResponse update(final UUID id, final UpdateProjectRequest request) {
        final ProjectRecord record = findProject(id);
        final String newName = request.name() == null || request.name().isBlank() ? record.name() : request.name();
        ensureProjectNameUnique(newName, id);

        final ProjectRecord updated = new ProjectRecord(
            record.id(),
            newName,
            request.description() == null ? record.description() : request.description(),
            record.ownerId(),
            new ArrayList<>(record.vms()),
            record.createdAt(),
            Instant.now()
        );

        projects.put(id, updated);
        return toResponse(updated);
    }

    public void delete(final UUID id) {
        findProject(id);
        projects.remove(id);
    }

    public ProjectResponse attachVm(final UUID id, final AttachVmRequest request) {
        final ProjectRecord record = findProject(id);
        final VirtualMachine vm = findVm(request.vmId());
        final List<VmResponse> vms = new ArrayList<>(record.vms());
        final VmResponse vmResponse = toVmResponse(vm);

        final boolean alreadyAttached = vms.stream().anyMatch(existing -> existing.id().equals(vmResponse.id()));
        if (!alreadyAttached) {
            vms.add(vmResponse);
        }

        final ProjectRecord updated = new ProjectRecord(
            record.id(),
            record.name(),
            record.description(),
            record.ownerId(),
            vms,
            record.createdAt(),
            Instant.now()
        );

        projects.put(id, updated);
        return toResponse(updated);
    }

    public void detachVm(final UUID id, final UUID vmId) {
        final ProjectRecord record = findProject(id);
        findVm(vmId);

        final List<VmResponse> vms = new ArrayList<>(record.vms());
        final boolean removed = vms.removeIf(existing -> existing.id().equals(vmId));
        if (!removed) {
            throw new ApiException(HttpStatus.NOT_FOUND, "VM not attached to project");
        }

        final ProjectRecord updated = new ProjectRecord(
            record.id(),
            record.name(),
            record.description(),
            record.ownerId(),
            vms,
            record.createdAt(),
            Instant.now()
        );

        projects.put(id, updated);
    }

    private void ensureProjectNameUnique(final String name, final UUID currentId) {
        final boolean exists = projects.values().stream()
            .anyMatch(record -> record.name().equals(name) && (currentId == null || !record.id().equals(currentId)));

        if (exists) {
            throw new ApiException(HttpStatus.CONFLICT, "Project with this name already exists");
        }
    }

    private ProjectRecord findProject(final UUID id) {
        final ProjectRecord record = projects.get(id);
        if (record == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Project not found");
        }

        return record;
    }

    private VirtualMachine findVm(final UUID vmId) {
        return virtualMachineRepository.findById(vmId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "VM not found"));
    }

    private ProjectResponse toResponse(final ProjectRecord record) {
        return new ProjectResponse(
            record.id(),
            record.name(),
            record.description(),
            record.ownerId(),
            List.copyOf(record.vms()),
            record.createdAt(),
            record.updatedAt()
        );
    }

    private VmResponse toVmResponse(final VirtualMachine vm) {
        return new VmResponse(
            vm.getId(),
            vm.getName(),
            vm.getHostname(),
            vm.getIpAddress(),
            vm.getVcpu(),
            vm.getMemoryMb(),
            vm.getDiskSizeGb(),
            vm.getOsImage(),
            vm.getStatus(),
            vm.getCreatedBy(),
            vm.getCreatedAt(),
            vm.getUpdatedAt()
        );
    }

    private record ProjectRecord(
        UUID id,
        String name,
        String description,
        UUID ownerId,
        List<VmResponse> vms,
        Instant createdAt,
        Instant updatedAt
    ) {
    }
}