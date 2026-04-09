package itmo.backend.services;

import itmo.backend.model.dto.deployment.CreateDeploymentRequest;
import itmo.backend.model.dto.deployment.DeploymentResponse;
import itmo.backend.model.dto.deployment.DeploymentStatus;
import itmo.backend.model.dto.deployment.PageDeploymentResponse;
import itmo.backend.model.dto.deployment.PageInfo;
import itmo.backend.model.entity.VirtualMachine;
import itmo.backend.model.exceptions.ApiException;
import itmo.backend.model.repository.VirtualMachineRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class DeploymentService {

    private final VirtualMachineRepository virtualMachineRepository;
    private final Map<UUID, DeploymentRecord> deployments = new ConcurrentHashMap<>();

    public DeploymentService(final VirtualMachineRepository virtualMachineRepository) {
        this.virtualMachineRepository = virtualMachineRepository;
    }

    public DeploymentResponse create(final CreateDeploymentRequest request) {
        final VirtualMachine vm = findVm(request.vmId());
        final DeploymentRecord record = new DeploymentRecord(
            UUID.randomUUID(),
            request.name(),
            request.version(),
            request.image(),
            request.replicas() == null ? 1 : request.replicas(),
            request.port(),
            DeploymentStatus.ACTIVE,
            vm.getId(),
            null,
            Instant.now(),
            Instant.now()
        );

        deployments.put(record.id(), record);
        return toResponse(record);
    }

    public PageDeploymentResponse list(final Pageable pageable, final DeploymentStatus status, final UUID vmId) {
        final List<DeploymentRecord> filtered = deployments.values().stream()
            .filter(record -> status == null || record.status() == status)
            .filter(record -> vmId == null || record.vmId().equals(vmId))
            .sorted(Comparator.comparing(DeploymentRecord::createdAt).reversed())
            .toList();

        final int page = pageable.getPageNumber();
        final int size = pageable.getPageSize();
        final int fromIndex = Math.min(page * size, filtered.size());
        final int toIndex = Math.min(fromIndex + size, filtered.size());

        return new PageDeploymentResponse(
            filtered.subList(fromIndex, toIndex).stream().map(this::toResponse).toList(),
            new PageInfo(page, size, filtered.size(), size == 0 ? 0 : (int) Math.ceil((double) filtered.size() / size))
        );
    }

    public DeploymentResponse getById(final UUID id) {
        return toResponse(findDeployment(id));
    }

    public void delete(final UUID id) {
        findDeployment(id);
        deployments.remove(id);
    }

    public DeploymentResponse rollback(final UUID id) {
        final DeploymentRecord record = findDeployment(id);
        final DeploymentRecord updated = record.withStatus(DeploymentStatus.ROLLED_BACK);
        deployments.put(id, updated);
        return toResponse(updated);
    }

    private VirtualMachine findVm(final UUID vmId) {
        return virtualMachineRepository.findById(vmId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "VM not found"));
    }

    private DeploymentRecord findDeployment(final UUID id) {
        final DeploymentRecord record = deployments.get(id);
        if (record == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Deployment not found");
        }

        return record;
    }

    private DeploymentResponse toResponse(final DeploymentRecord record) {
        return new DeploymentResponse(
            record.id(),
            record.name(),
            record.version(),
            record.image(),
            record.replicas(),
            record.port(),
            record.status(),
            record.vmId(),
            record.deployedBy(),
            record.createdAt(),
            record.updatedAt()
        );
    }

    private record DeploymentRecord(
        UUID id,
        String name,
        String version,
        String image,
        Integer replicas,
        Integer port,
        DeploymentStatus status,
        UUID vmId,
        UUID deployedBy,
        Instant createdAt,
        Instant updatedAt
    ) {
        private DeploymentRecord withStatus(final DeploymentStatus newStatus) {
            return new DeploymentRecord(
                id,
                name,
                version,
                image,
                replicas,
                port,
                newStatus,
                vmId,
                deployedBy,
                createdAt,
                Instant.now()
            );
        }
    }
}