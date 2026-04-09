package itmo.backend.services;

import itmo.backend.model.dto.vm.CreateVmRequest;
import itmo.backend.model.dto.vm.UpdateVmRequest;
import itmo.backend.model.dto.vm.VmResponse;
import itmo.backend.model.entity.VirtualMachine;
import itmo.backend.model.entity.VmStatus;
import itmo.backend.model.exceptions.ApiException;
import itmo.backend.model.repository.VirtualMachineRepository;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class VirtualMachineService {

    private final VirtualMachineRepository virtualMachineRepository;

    public VirtualMachineService(final VirtualMachineRepository virtualMachineRepository) {
        this.virtualMachineRepository = virtualMachineRepository;
    }

    public VmResponse create(final CreateVmRequest request) {
        if (virtualMachineRepository.findByName(request.name()).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "VM with this name already exists");
        }

        final String hostname = request.hostname() == null || request.hostname().isBlank()
            ? request.name()
            : request.hostname();

        final VirtualMachine vm = new VirtualMachine(
            request.name(),
            hostname,
            null,
            VmStatus.STOPPED,
            request.vcpu(),
            request.memoryMb(),
            request.diskSizeGb(),
            request.osImage(),
            null
        );

        final VirtualMachine saved = virtualMachineRepository.save(vm);
        return toResponse(saved);
    }

    public Page<VmResponse> list(final Pageable pageable) {
        return virtualMachineRepository.findAll(pageable).map(this::toResponse);
    }

    public VmResponse getById(final UUID id) {
        final VirtualMachine vm = virtualMachineRepository.findById(id)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "VM not found"));

        return toResponse(vm);
    }

    public VmResponse update(final UUID id, final UpdateVmRequest request) {
        final VirtualMachine vm = virtualMachineRepository.findById(id)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "VM not found"));

        vm.update(request.hostname(), request.vcpu(), request.memoryMb());
        final VirtualMachine saved = virtualMachineRepository.save(vm);
        return toResponse(saved);
    }

    public void delete(final UUID id) {
        final VirtualMachine vm = virtualMachineRepository.findById(id)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "VM not found"));

        virtualMachineRepository.delete(vm);
    }

    private VmResponse toResponse(final VirtualMachine vm) {
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
}
