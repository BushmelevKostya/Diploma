package itmo.backend.services;

import itmo.backend.model.entity.VirtualMachine;
import itmo.backend.model.entity.VmStatus;
import itmo.backend.model.repository.VirtualMachineRepository;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class VmProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(VmProvisioningService.class);

    private final VirtualMachineRepository virtualMachineRepository;
    private final InfrastructureCommandService infrastructureCommandService;
    private final ReentrantLock infrastructureLock = new ReentrantLock();

    public VmProvisioningService(
        final VirtualMachineRepository virtualMachineRepository,
        final InfrastructureCommandService infrastructureCommandService
    ) {
        this.virtualMachineRepository = virtualMachineRepository;
        this.infrastructureCommandService = infrastructureCommandService;
    }

    @Async("vmProvisioningExecutor")
    public void provisionVm(final UUID vmId) {
        log.info("Provisioning workflow queued for VM {}", vmId);
        try {
            provisionVmSync(vmId);
        } catch (final Exception exception) {
            log.error("VM provisioning failed for {}", vmId, exception);
            markVmAsFailed(vmId, exception);
        }
    }

    public void reprovisionAll() throws IOException, InterruptedException {
        log.info("Reprovisioning all VMs from desired state");
        acquireInfrastructureLock("reprovision-all");
        try {
            final List<VirtualMachine> virtualMachines = virtualMachineRepository.findAll().stream()
                .sorted((left, right) -> left.getCreatedAt().compareTo(right.getCreatedAt()))
                .toList();

            infrastructureCommandService.writeDesiredState(virtualMachines);
            infrastructureCommandService.applyDesiredState();
        } finally {
            log.info("Infrastructure lock released for reprovision-all");
            infrastructureLock.unlock();
        }
    }

  public void deprovisionVm(final UUID vmId) throws IOException, InterruptedException {
    log.info("Deprovisioning VM {}", vmId);
    if (!tryAcquireInfrastructureLock("deprovision-" + vmId, 30)) {
      throw new IOException("Could not acquire infrastructure lock for deprovision within 30 seconds. " +
        "Another operation is in progress.");
    }
    try {
      log.info("Infrastructure lock acquired for deprovision {}", vmId);
      final List<VirtualMachine> remaining = virtualMachineRepository.findAll().stream()
        .filter(vm -> !vm.getId().equals(vmId))
        .sorted((left, right) -> left.getCreatedAt().compareTo(right.getCreatedAt()))
        .toList();
      log.info("Remaining desired state after delete contains {} VM(s)", remaining.size());

      infrastructureCommandService.writeDesiredState(remaining);
      infrastructureCommandService.destroyDesiredState();
      log.info("OpenTofu reconcile finished for deprovision {}", vmId);

      if (!remaining.isEmpty()) {
        try {
          final Map<String, String> vmIps = collectReachableIps(remaining);
          infrastructureCommandService.writeAnsibleInventory(remaining, vmIps);
          log.info("Ansible inventory refreshed after deprovision {}", vmId);
        } catch (final Exception exception) {
          log.warn("Failed to refresh Ansible inventory after deprovision {}: {}", vmId, exception.getMessage());
        }
      } else {
        infrastructureCommandService.writeAnsibleInventory(List.of(), Map.of());
        log.info("Ansible inventory cleared (no remaining VMs)");
      }
    } finally {
      log.info("Infrastructure lock released for deprovision {}", vmId);
      infrastructureLock.unlock();
    }
  }

    public String refreshIpAddress(final VirtualMachine virtualMachine) throws IOException, InterruptedException {
        log.info("Refreshing IP address for VM {} ({})", virtualMachine.getName(), virtualMachine.getId());
        final String ipAddress = infrastructureCommandService.waitForVmIp(virtualMachine.getName());
        virtualMachine.markIpAddress(ipAddress, "IP address discovered");
        virtualMachineRepository.save(virtualMachine);
        log.info("Saved refreshed IP {} for VM {}", ipAddress, virtualMachine.getName());
        return ipAddress;
    }

    private void provisionVmSync(final UUID vmId) throws IOException, InterruptedException {
        log.info("Provisioning workflow started for VM {}", vmId);
        acquireInfrastructureLock("provision-" + vmId);
        try {
            final VirtualMachine virtualMachine = getVm(vmId);
            log.info("Provisioning lock acquired for VM {} ({})", virtualMachine.getName(), vmId);

            virtualMachine.markCreating("Preparing OpenTofu desired state");
            virtualMachineRepository.save(virtualMachine);
            log.info("VM {} marked as CREATING: preparing desired state", virtualMachine.getName());

            final List<VirtualMachine> allVirtualMachines = virtualMachineRepository.findAll().stream()
                .sorted((left, right) -> left.getCreatedAt().compareTo(right.getCreatedAt()))
                .toList();
            log.info("Desired state includes {} VM(s)", allVirtualMachines.size());

            infrastructureCommandService.writeDesiredState(allVirtualMachines);
            infrastructureCommandService.applyDesiredState();
            log.info("OpenTofu apply finished for VM {}", virtualMachine.getName());

            virtualMachine.markCreating("Waiting for DHCP lease");
            virtualMachineRepository.save(virtualMachine);
            log.info("VM {} marked as waiting for DHCP lease", virtualMachine.getName());

            final String ipAddress = infrastructureCommandService.waitForVmIp(virtualMachine.getName());
            log.info("VM {} received IP {}", virtualMachine.getName(), ipAddress);

            final Map<String, String> vmIps = collectReachableIps(allVirtualMachines, virtualMachine.getName(), ipAddress);
            infrastructureCommandService.writeAnsibleInventory(allVirtualMachines, vmIps);
            log.info("Ansible inventory refreshed after provisioning {}", virtualMachine.getName());

            virtualMachine.markCreating("Applying Ansible base configuration");
            virtualMachine.markIpAddress(ipAddress, "Applying Ansible base configuration");
            virtualMachineRepository.save(virtualMachine);
            log.info("VM {} marked as configuring with Ansible", virtualMachine.getName());

          final String playbookFile = infrastructureCommandService.getOsPlaybook(virtualMachine.getOsImage());

          infrastructureCommandService.runAnsibleForVmWithPlaybook(virtualMachine.getName(), playbookFile);
            log.info("Ansible completed for VM {}", virtualMachine.getName());

            virtualMachine.markRunning(ipAddress, "Provisioning completed");
            virtualMachineRepository.save(virtualMachine);
            log.info("Provisioning workflow completed for VM {} with IP {}", virtualMachine.getName(), ipAddress);
        } finally {
            log.info("Provisioning lock released for VM {}", vmId);
            infrastructureLock.unlock();
        }
    }

    private void acquireInfrastructureLock(final String operationName) throws InterruptedException {
        log.info("Waiting for infrastructure lock for {}", operationName);
        while (!infrastructureLock.tryLock(5, TimeUnit.SECONDS)) {
            log.info("Infrastructure lock is still busy, {} continues waiting", operationName);
        }
        log.info("Infrastructure lock acquired for {}", operationName);
    }

  private boolean tryAcquireInfrastructureLock(final String operationName, final int maxWaitSeconds)
    throws InterruptedException {
    log.info("Waiting for infrastructure lock for {} (max {} seconds)", operationName, maxWaitSeconds);
    int waited = 0;
    while (!infrastructureLock.tryLock(5, TimeUnit.SECONDS)) {
      waited += 5;
      log.info("Infrastructure lock is still busy, {} waited {} second(s)", operationName, waited);
      if (waited >= maxWaitSeconds) {
        log.warn("Gave up waiting for infrastructure lock for {}", operationName);
        return false;
      }
    }
    log.info("Infrastructure lock acquired for {}", operationName);
    return true;
  }

    private Map<String, String> collectReachableIps(final List<VirtualMachine> virtualMachines)
        throws IOException, InterruptedException {
        final Map<String, String> vmIps = new LinkedHashMap<>();
        for (VirtualMachine virtualMachine : virtualMachines) {
            if (virtualMachine.getStatus() == VmStatus.ERROR) {
                log.info("Skipping VM {} while collecting IPs because it is in ERROR status", virtualMachine.getName());
                continue;
            }

            final String ipAddress = virtualMachine.getIpAddress() == null || virtualMachine.getIpAddress().isBlank()
                ? infrastructureCommandService.waitForVmIp(virtualMachine.getName())
                : virtualMachine.getIpAddress();
            vmIps.put(virtualMachine.getName(), ipAddress);
            log.info("Collected IP {} for VM {}", ipAddress, virtualMachine.getName());
        }

        return vmIps;
    }

    private Map<String, String> collectReachableIps(
        final List<VirtualMachine> virtualMachines,
        final String requiredVmName,
        final String requiredVmIp
    ) throws IOException, InterruptedException {
        final Map<String, String> vmIps = new LinkedHashMap<>();
        for (VirtualMachine virtualMachine : virtualMachines) {
            if (virtualMachine.getStatus() == VmStatus.ERROR && !virtualMachine.getName().equals(requiredVmName)) {
                log.info("Skipping VM {} while collecting IPs because it is in ERROR status", virtualMachine.getName());
                continue;
            }

            final String ipAddress;
            if (virtualMachine.getName().equals(requiredVmName)) {
                ipAddress = requiredVmIp;
            } else if (virtualMachine.getIpAddress() != null && !virtualMachine.getIpAddress().isBlank()) {
                ipAddress = virtualMachine.getIpAddress();
            } else {
                ipAddress = infrastructureCommandService.waitForVmIp(virtualMachine.getName());
            }

            vmIps.put(virtualMachine.getName(), ipAddress);
            log.info("Collected IP {} for VM {}", ipAddress, virtualMachine.getName());
        }

        return vmIps;
    }

    private VirtualMachine getVm(final UUID vmId) {
        return virtualMachineRepository.findById(vmId)
            .orElseThrow(() -> new IllegalStateException("VM not found: " + vmId));
    }

    private void markVmAsFailed(final UUID vmId, final Exception exception) {
        virtualMachineRepository.findById(vmId).ifPresent(virtualMachine -> {
            final String errorMessage = rootMessage(exception);
            log.error("Marking VM {} ({}) as ERROR: {}", virtualMachine.getName(), vmId, errorMessage);
            virtualMachine.markError(errorMessage);
            virtualMachineRepository.save(virtualMachine);
        });
    }

    private String rootMessage(final Exception exception) {
        Throwable current = exception;
        while (current.getCause() != null) {
            current = current.getCause();
        }

        if (current.getMessage() == null || current.getMessage().isBlank()) {
            return "Infrastructure provisioning failed";
        }

        final String normalized = current.getMessage()
            .replace("\u0000", "")
            .replace('\uFEFF', ' ')
            .replace('\r', ' ')
            .replace('\n', ' ')
            .replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", " ")
            .replaceAll("\\s+", " ")
            .trim();

        if (normalized.length() <= 300) {
            return normalized;
        }

        return normalized.substring(0, 297) + "...";
    }
}
