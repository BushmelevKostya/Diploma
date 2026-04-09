package itmo.backend.controller;

import itmo.backend.model.dto.deployment.CreateDeploymentRequest;
import itmo.backend.model.dto.deployment.DeploymentResponse;
import itmo.backend.model.dto.deployment.DeploymentStatus;
import itmo.backend.model.dto.deployment.PageDeploymentResponse;
import itmo.backend.services.DeploymentService;
import jakarta.validation.Valid;
import java.util.UUID;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/deployments")
public class DeploymentController {

    private final DeploymentService deploymentService;

    public DeploymentController(final DeploymentService deploymentService) {
        this.deploymentService = deploymentService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DeploymentResponse create(@Valid @RequestBody final CreateDeploymentRequest request) {
        return deploymentService.create(request);
    }

    @GetMapping
    public PageDeploymentResponse list(
        @RequestParam(defaultValue = "0") final int page,
        @RequestParam(defaultValue = "20") final int size,
        @RequestParam(required = false) final DeploymentStatus status,
        @RequestParam(required = false) final UUID vmId
    ) {
        return deploymentService.list(PageRequest.of(page, size), status, vmId);
    }

    @GetMapping("/{id}")
    public DeploymentResponse getById(@PathVariable final UUID id) {
        return deploymentService.getById(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable final UUID id) {
        deploymentService.delete(id);
    }

    @PostMapping("/{id}/rollback")
    public DeploymentResponse rollback(@PathVariable final UUID id) {
        return deploymentService.rollback(id);
    }
}