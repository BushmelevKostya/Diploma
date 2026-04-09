package itmo.backend.controller;

import itmo.backend.model.dto.vm.CreateVmRequest;
import itmo.backend.model.dto.vm.MetricResponse;
import itmo.backend.model.dto.vm.MetricType;
import itmo.backend.model.dto.vm.UpdateVmRequest;
import itmo.backend.model.dto.vm.VmResponse;
import itmo.backend.services.VirtualMachineService;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/vms")
public class VirtualMachineController {

    private final VirtualMachineService virtualMachineService;

    public VirtualMachineController(final VirtualMachineService virtualMachineService) {
        this.virtualMachineService = virtualMachineService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public VmResponse create(@Valid @RequestBody final CreateVmRequest request) {
        return virtualMachineService.create(request);
    }

    @GetMapping
    public Page<VmResponse> list(
        @RequestParam(defaultValue = "0") final int page,
        @RequestParam(defaultValue = "20") final int size
    ) {
        return virtualMachineService.list(PageRequest.of(page, size));
    }

    @GetMapping("/{id}")
    public VmResponse getById(@PathVariable final UUID id) {
        return virtualMachineService.getById(id);
    }

    @PutMapping("/{id}")
    public VmResponse update(
        @PathVariable final UUID id,
        @Valid @RequestBody final UpdateVmRequest request
    ) {
        return virtualMachineService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable final UUID id) {
        virtualMachineService.delete(id);
    }

    @PostMapping("/{id}/start")
    public VmResponse start(@PathVariable final UUID id) {
        return virtualMachineService.start(id);
    }

    @PostMapping("/{id}/stop")
    public VmResponse stop(@PathVariable final UUID id) {
        return virtualMachineService.stop(id);
    }

    @GetMapping("/{id}/metrics")
    public List<MetricResponse> metrics(
        @PathVariable final UUID id,
        @RequestParam(required = false) final MetricType type,
        @RequestParam(required = false) final Instant from,
        @RequestParam(required = false) final Instant to
    ) {
        return virtualMachineService.getMetrics(id, type);
    }
}
