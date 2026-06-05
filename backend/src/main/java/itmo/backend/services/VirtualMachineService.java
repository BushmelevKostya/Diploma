package itmo.backend.services;

import itmo.backend.model.dto.vm.CreateVmRequest;
import itmo.backend.model.dto.vm.EnvironmentPackage;
import itmo.backend.model.dto.vm.EnvironmentPackageOptionResponse;
import itmo.backend.model.dto.vm.MetricResponse;
import itmo.backend.model.dto.vm.MetricType;
import itmo.backend.model.dto.vm.PageInfo;
import itmo.backend.model.dto.vm.PageVmResponse;
import itmo.backend.model.dto.vm.UpdateVmRequest;
import itmo.backend.model.dto.vm.VmResponse;
import itmo.backend.model.entity.VirtualMachine;
import itmo.backend.model.entity.VmStatus;
import itmo.backend.model.exceptions.ApiException;
import itmo.backend.model.repository.VirtualMachineRepository;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class VirtualMachineService {
    private static final Logger log = LoggerFactory.getLogger(VirtualMachineService.class);

    private final VirtualMachineRepository virtualMachineRepository;
    private final VmProvisioningService vmProvisioningService;
    private final InfrastructureCommandService infrastructureCommandService;

    public VirtualMachineService(
        final VirtualMachineRepository virtualMachineRepository,
        final VmProvisioningService vmProvisioningService,
        final InfrastructureCommandService infrastructureCommandService
    ) {
        this.virtualMachineRepository = virtualMachineRepository;
        this.vmProvisioningService = vmProvisioningService;
        this.infrastructureCommandService = infrastructureCommandService;
    }

    public VmResponse create(final CreateVmRequest request) {
        log.info("Received create VM request: name={}, hostname={}, vcpu={}, memoryMb={}, diskSizeGb={}, osImage={}, environmentPackages={}",
            request.name(), request.hostname(), request.vcpu(), request.memoryMb(), request.diskSizeGb(), request.osImage(), request.environmentPackages());
        if (virtualMachineRepository.findByName(request.name()).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "VM with this name already exists");
        }

        final String hostname = request.hostname() == null || request.hostname().isBlank()
            ? request.name()
            : request.hostname();
        final String osImage = infrastructureCommandService.normalizeOsImage(request.osImage());
        final Set<EnvironmentPackage> environmentPackages = normalizeEnvironmentPackages(request.environmentPackages());

        final VirtualMachine vm = new VirtualMachine(
            request.name(),
            hostname,
            null,
            "Provisioning has been queued",
            VmStatus.CREATING,
            request.vcpu(),
            request.memoryMb(),
            request.diskSizeGb(),
            osImage,
            environmentPackages,
            null
        );

        final VirtualMachine saved = virtualMachineRepository.save(vm);
        log.info("VM {} ({}) created in database with status {}", saved.getName(), saved.getId(), saved.getStatus());
        if (infrastructureCommandService.isEnabled()) {
            vmProvisioningService.provisionVm(saved.getId());
        } else {
            saved.markRunning(null, "Infrastructure provisioning is disabled");
            return toResponse(virtualMachineRepository.save(saved));
        }
        return toResponse(saved);
    }

    public List<EnvironmentPackageOptionResponse> environmentPackageOptions() {
        return List.of(
            new EnvironmentPackageOptionResponse(
                EnvironmentPackage.SSH,
                "SSH",
                "Гарантированно устанавливает и включает openssh-server внутри VM."
            ),
            new EnvironmentPackageOptionResponse(
                EnvironmentPackage.DOCKER,
                "Docker",
                "Устанавливает docker.io и добавляет пользователя ubuntu в группу docker."
            ),
            new EnvironmentPackageOptionResponse(
                EnvironmentPackage.HTTP_SERVER,
                "HTTP Server",
                "Готовит простой пустой HTTP-сайт и команду diploma-http-demo для запуска из консоли."
            )
        );
    }

    public PageVmResponse list(final Pageable pageable, final VmStatus status) {
        final Page<VirtualMachine> pageResult = status == null
            ? virtualMachineRepository.findAll(pageable)
            : virtualMachineRepository.findAllByStatus(status, pageable);

        return new PageVmResponse(
            pageResult.getContent().stream().map(this::toResponse).toList(),
            new PageInfo(
                pageResult.getNumber(),
                pageResult.getSize(),
                pageResult.getTotalElements(),
                pageResult.getTotalPages()
            )
        );
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
    log.info("Deleting VM {} ({})", vm.getName(), vm.getId());

    if (infrastructureCommandService.isEnabled()) {
      try {
        vmProvisioningService.deprovisionVm(id);
        log.info("Infrastructure deprovision finished for VM {} ({})", vm.getName(), vm.getId());
      } catch (final Exception exception) {
        log.warn("Infrastructure deprovision failed for VM {} ({}), proceeding with database deletion: {}",
          vm.getName(), vm.getId(), exception.getMessage());
      }
    }

    virtualMachineRepository.delete(vm);
    log.info("VM {} ({}) deleted from database", vm.getName(), vm.getId());
  }

    public VmResponse start(final UUID id) {
        final VirtualMachine vm = virtualMachineRepository.findById(id)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "VM not found"));
        log.info("Starting VM {} ({})", vm.getName(), vm.getId());

        if (vm.getStatus() == VmStatus.RUNNING) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VM is already running");
        }

        try {
            if (infrastructureCommandService.isEnabled()) {
                infrastructureCommandService.startVm(vm.getName());
                if (vm.getIpAddress() == null || vm.getIpAddress().isBlank()) {
                    vmProvisioningService.refreshIpAddress(vm);
                }
            }
            vm.markDesiredStatus(VmStatus.RUNNING);
            vm.start();
        } catch (final Exception exception) {
            log.error("Failed to start VM {} ({})", vm.getName(), vm.getId(), exception);
            vm.markError(exception.getMessage());
        }
        return toResponse(virtualMachineRepository.save(vm));
    }

    public VmResponse stop(final UUID id) {
        final VirtualMachine vm = virtualMachineRepository.findById(id)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "VM not found"));
        log.info("Stopping VM {} ({})", vm.getName(), vm.getId());

        if (vm.getStatus() == VmStatus.STOPPED) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VM is already stopped");
        }

        try {
            if (infrastructureCommandService.isEnabled()) {
                infrastructureCommandService.stopVm(vm.getName());
            }
            vm.markDesiredStatus(VmStatus.STOPPED);
            vm.stop();
        } catch (final Exception exception) {
            log.error("Failed to stop VM {} ({})", vm.getName(), vm.getId(), exception);
            vm.markError(exception.getMessage());
        }
        return toResponse(virtualMachineRepository.save(vm));
    }

    public List<MetricResponse> getMetrics(final UUID id, final MetricType type) {
        final VirtualMachine vm = virtualMachineRepository.findById(id)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "VM not found"));

        final List<MetricResponse> metrics = List.of(
            metric(vm, MetricType.CPU, cpuValue(vm), "%"),
            metric(vm, MetricType.MEMORY, memoryValue(vm), "MB"),
            metric(vm, MetricType.DISK, diskValue(vm), "GB"),
            metric(vm, MetricType.NETWORK, networkValue(vm), "Mbps")
        );

        if (type == null) {
            return metrics;
        }

        return metrics.stream()
            .filter(metric -> metric.metricType() == type)
            .toList();
    }

    private VmResponse toResponse(final VirtualMachine vm) {
        return new VmResponse(
            vm.getId(),
            vm.getName(),
            vm.getHostname(),
            vm.getIpAddress(),
            vm.getStatusMessage(),
            vm.getVcpu(),
            vm.getMemoryMb(),
            vm.getDiskSizeGb(),
            vm.getOsImage(),
            vm.getEnvironmentPackages(),
            vm.getStatus(),
            vm.getCreatedBy(),
            vm.getCreatedAt(),
            vm.getUpdatedAt()
        );
    }

    private Set<EnvironmentPackage> normalizeEnvironmentPackages(final List<EnvironmentPackage> requestedPackages) {
        if (requestedPackages == null || requestedPackages.isEmpty()) {
            return new LinkedHashSet<>();
        }

        return new LinkedHashSet<>(requestedPackages);
    }

    private MetricResponse metric(
        final VirtualMachine vm,
        final MetricType metricType,
        final Double value,
        final String unit
    ) {
        return new MetricResponse(
            UUID.randomUUID(),
            vm.getId(),
            vm.getName(),
            metricType,
            value,
            unit,
            Instant.now()
        );
    }

    private Double cpuValue(final VirtualMachine vm) {
        if (vm.getStatus() == VmStatus.STOPPED) {
            return 0.0;
        }

        return Math.min(100.0, vm.getVcpu() * 12.5);
    }

    private Double memoryValue(final VirtualMachine vm) {
        return vm.getStatus() == VmStatus.STOPPED ? 0.0 : vm.getMemoryMb().doubleValue();
    }

    private Double diskValue(final VirtualMachine vm) {
        return vm.getDiskSizeGb().doubleValue();
    }

    private Double networkValue(final VirtualMachine vm) {
        return vm.getStatus() == VmStatus.RUNNING ? 12.0 : 0.0;
    }
}
