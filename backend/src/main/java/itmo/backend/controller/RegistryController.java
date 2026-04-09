package itmo.backend.controller;

import itmo.backend.model.dto.registry.CreateServiceRequest;
import itmo.backend.model.dto.registry.PageServiceResponse;
import itmo.backend.model.dto.registry.ServiceResponse;
import itmo.backend.model.dto.registry.ServiceStatus;
import itmo.backend.model.dto.registry.ServiceType;
import itmo.backend.model.dto.registry.UpdateServiceRequest;
import itmo.backend.registry.RegistryService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/services")
public class RegistryController {

    private final RegistryService registryService;

    public RegistryController(final RegistryService registryService) {
        this.registryService = registryService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ServiceResponse create(@Valid @RequestBody final CreateServiceRequest request) {
        return registryService.create(request);
    }

    @GetMapping
    public PageServiceResponse list(
        @RequestParam(defaultValue = "0") final int page,
        @RequestParam(defaultValue = "20") final int size,
        @RequestParam(required = false) final ServiceType type,
        @RequestParam(required = false) final ServiceStatus status
    ) {
        return registryService.list(PageRequest.of(page, size), type, status);
    }

    @GetMapping("/{id}")
    public ServiceResponse getById(@PathVariable final UUID id) {
        return registryService.getById(id);
    }

    @PutMapping("/{id}")
    public ServiceResponse update(
        @PathVariable final UUID id,
        @RequestBody final UpdateServiceRequest request
    ) {
        return registryService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable final UUID id) {
        registryService.delete(id);
    }
}