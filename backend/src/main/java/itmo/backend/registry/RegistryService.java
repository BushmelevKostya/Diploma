package itmo.backend.registry;

import itmo.backend.model.dto.registry.CreateServiceRequest;
import itmo.backend.model.dto.registry.PageInfo;
import itmo.backend.model.dto.registry.PageServiceResponse;
import itmo.backend.model.dto.registry.ServiceResponse;
import itmo.backend.model.dto.registry.ServiceStatus;
import itmo.backend.model.dto.registry.ServiceType;
import itmo.backend.model.dto.registry.UpdateServiceRequest;
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
public class RegistryService {

    private final VirtualMachineRepository virtualMachineRepository;
    private final Map<UUID, ManagedServiceRecord> services = new ConcurrentHashMap<>();

    public RegistryService(final VirtualMachineRepository virtualMachineRepository) {
        this.virtualMachineRepository = virtualMachineRepository;
    }

    public ServiceResponse create(final CreateServiceRequest request) {
        final VirtualMachine vm = findVm(request.vmId());
        final ManagedServiceRecord record = new ManagedServiceRecord(
            UUID.randomUUID(),
            request.name(),
            request.description(),
            request.serviceType(),
            ServiceStatus.RUNNING,
            request.url(),
            vm.getId(),
            Instant.now(),
            Instant.now()
        );

        services.put(record.id(), record);
        return toResponse(record);
    }

    public PageServiceResponse list(final Pageable pageable, final ServiceType type, final ServiceStatus status) {
        final List<ManagedServiceRecord> filtered = services.values().stream()
            .filter(record -> type == null || record.serviceType() == type)
            .filter(record -> status == null || record.status() == status)
            .sorted(Comparator.comparing(ManagedServiceRecord::createdAt).reversed())
            .toList();

        final int page = pageable.getPageNumber();
        final int size = pageable.getPageSize();
        final int fromIndex = Math.min(page * size, filtered.size());
        final int toIndex = Math.min(fromIndex + size, filtered.size());

        return new PageServiceResponse(
            filtered.subList(fromIndex, toIndex).stream().map(this::toResponse).toList(),
            new PageInfo(page, size, filtered.size(), size == 0 ? 0 : (int) Math.ceil((double) filtered.size() / size))
        );
    }

    public ServiceResponse getById(final UUID id) {
        return toResponse(findService(id));
    }

    public ServiceResponse update(final UUID id, final UpdateServiceRequest request) {
        final ManagedServiceRecord record = findService(id);
        final ManagedServiceRecord updated = new ManagedServiceRecord(
            record.id(),
            record.name(),
            request.description() == null ? record.description() : request.description(),
            record.serviceType(),
            request.status() == null ? record.status() : request.status(),
            request.url() == null ? record.url() : request.url(),
            record.vmId(),
            record.createdAt(),
            Instant.now()
        );

        services.put(id, updated);
        return toResponse(updated);
    }

    public void delete(final UUID id) {
        findService(id);
        services.remove(id);
    }

    private VirtualMachine findVm(final UUID vmId) {
        return virtualMachineRepository.findById(vmId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "VM not found"));
    }

    private ManagedServiceRecord findService(final UUID id) {
        final ManagedServiceRecord record = services.get(id);
        if (record == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Service not found");
        }

        return record;
    }

    private ServiceResponse toResponse(final ManagedServiceRecord record) {
        return new ServiceResponse(
            record.id(),
            record.name(),
            record.description(),
            record.serviceType(),
            record.status(),
            record.url(),
            record.vmId(),
            record.createdAt(),
            record.updatedAt()
        );
    }

    private record ManagedServiceRecord(
        UUID id,
        String name,
        String description,
        ServiceType serviceType,
        ServiceStatus status,
        String url,
        UUID vmId,
        Instant createdAt,
        Instant updatedAt
    ) {
    }
}