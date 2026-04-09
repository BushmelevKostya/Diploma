package itmo.backend.services;

import itmo.backend.model.dto.vm.CreateVmRequest;
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

        final VirtualMachine vm = new VirtualMachine(
            request.name(),
            null,
            VmStatus.STOPPED,
            request.cpuCores(),
            request.memoryMb(),
            request.diskGb()
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

    private VmResponse toResponse(final VirtualMachine vm) {
        return new VmResponse(
            vm.getId(),
            vm.getName(),
            vm.getIpAddress(),
            vm.getStatus(),
            vm.getCpuCores(),
            vm.getMemoryMb(),
            vm.getDiskGb(),
            vm.getCreatedAt()
        );
    }
}
