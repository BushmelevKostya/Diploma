package itmo.backend.controller;

import itmo.backend.model.dto.project.AttachVmRequest;
import itmo.backend.model.dto.project.CreateProjectRequest;
import itmo.backend.model.dto.project.PageProjectResponse;
import itmo.backend.model.dto.project.ProjectResponse;
import itmo.backend.model.dto.project.UpdateProjectRequest;
import itmo.backend.services.ProjectService;
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
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(final ProjectService projectService) {
        this.projectService = projectService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectResponse create(@Valid @RequestBody final CreateProjectRequest request) {
        return projectService.create(request);
    }

    @GetMapping
    public PageProjectResponse list(
        @RequestParam(defaultValue = "0") final int page,
        @RequestParam(defaultValue = "20") final int size
    ) {
        return projectService.list(PageRequest.of(page, size));
    }

    @GetMapping("/{id}")
    public ProjectResponse getById(@PathVariable final UUID id) {
        return projectService.getById(id);
    }

    @PutMapping("/{id}")
    public ProjectResponse update(
        @PathVariable final UUID id,
        @RequestBody final UpdateProjectRequest request
    ) {
        return projectService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable final UUID id) {
        projectService.delete(id);
    }

    @PostMapping("/{id}/vms")
    public ProjectResponse attachVm(
        @PathVariable final UUID id,
        @Valid @RequestBody final AttachVmRequest request
    ) {
        return projectService.attachVm(id, request);
    }

    @DeleteMapping("/{id}/vms/{vmId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void detachVm(
        @PathVariable final UUID id,
        @PathVariable final UUID vmId
    ) {
        projectService.detachVm(id, vmId);
    }
}