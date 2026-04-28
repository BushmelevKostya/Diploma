package itmo.backend.services;

import itmo.backend.model.dto.drift.DriftDifference;
import itmo.backend.model.dto.drift.DriftReportResponse;
import itmo.backend.model.dto.drift.DriftStatus;
import itmo.backend.model.dto.drift.PageDriftReportResponse;
import itmo.backend.model.dto.drift.PageInfo;
import itmo.backend.model.dto.vm.EnvironmentPackage;
import itmo.backend.model.entity.VirtualMachine;
import itmo.backend.model.entity.VmStatus;
import itmo.backend.model.exceptions.ApiException;
import itmo.backend.model.repository.VirtualMachineRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class DriftService {

  private final VirtualMachineRepository virtualMachineRepository;
  private final InfrastructureCommandService infrastructureCommandService;
  private final Map<UUID, DriftRecord> reports = new ConcurrentHashMap<>();

  public DriftService(
    final VirtualMachineRepository virtualMachineRepository,
    final InfrastructureCommandService infrastructureCommandService
  ) {
    this.virtualMachineRepository = virtualMachineRepository;
    this.infrastructureCommandService = infrastructureCommandService;
  }

  public DriftReportResponse createReport(final UUID vmId) {
    final VirtualMachine vm = findVm(vmId);
    final Map<String, Object> expectedState = expectedState(vm);
    final Map<String, Object> actualState = actualState(vm);
    final List<DriftDifference> differences = differences(expectedState, actualState);
    final DriftStatus status = differences.isEmpty() ? DriftStatus.CLEAN : DriftStatus.DRIFTED;

    final DriftRecord record = new DriftRecord(
      UUID.randomUUID(),
      vm.getId(),
      vm.getName(),
      status,
      expectedState,
      actualState,
      differences,
      Instant.now(),
      Instant.now()
    );

    reports.put(record.id(), record);
    return toResponse(record);
  }

  public PageDriftReportResponse list(final Pageable pageable, final DriftStatus status, final UUID vmId) {
    final List<DriftRecord> filtered = reports.values().stream()
      .filter(record -> status == null || record.status() == status)
      .filter(record -> vmId == null || record.vmId().equals(vmId))
      .sorted(Comparator.comparing(DriftRecord::createdAt).reversed())
      .toList();

    final int page = pageable.getPageNumber();
    final int size = pageable.getPageSize();
    final int fromIndex = Math.min(page * size, filtered.size());
    final int toIndex = Math.min(fromIndex + size, filtered.size());

    return new PageDriftReportResponse(
      filtered.subList(fromIndex, toIndex).stream().map(this::toResponse).toList(),
      new PageInfo(page, size, filtered.size(), size == 0 ? 0 : (int) Math.ceil((double) filtered.size() / size))
    );
  }

  public DriftReportResponse getById(final UUID id) {
    return toResponse(findReport(id));
  }

  private VirtualMachine findVm(final UUID vmId) {
    return virtualMachineRepository.findById(vmId)
      .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "VM not found"));
  }

  private DriftRecord findReport(final UUID id) {
    final DriftRecord record = reports.get(id);
    if (record == null) {
      throw new ApiException(HttpStatus.NOT_FOUND, "Drift report not found");
    }

    return record;
  }

  private DriftReportResponse toResponse(final DriftRecord record) {
    return new DriftReportResponse(
      record.id(),
      record.vmId(),
      record.vmName(),
      record.status(),
      record.expectedState(),
      record.actualState(),
      record.differences(),
      record.checkedAt(),
      record.createdAt()
    );
  }

  private Map<String, Object> expectedState(final VirtualMachine vm) {
    final VmStatus desiredStatus = vm.getDesiredStatus() == null ? VmStatus.RUNNING : vm.getDesiredStatus();
    return Map.of(
      "name", vm.getName(),
      "hostname", vm.getHostname(),
      "vcpu", vm.getVcpu(),
      "memoryMb", vm.getMemoryMb(),
      "diskSizeGb", vm.getDiskSizeGb(),
      "osImage", vm.getOsImage(),
      "environmentPackages", environmentPackagesValue(vm.getEnvironmentPackages()),
      "status", desiredStatus.name()
    );
  }

  private Map<String, Object> actualState(final VirtualMachine vm) {
    final String actualStatus = resolveActualStatus(vm);
    return Map.of(
      "name", vm.getName(),
      "hostname", vm.getHostname(),
      "vcpu", vm.getVcpu(),
      "memoryMb", vm.getMemoryMb(),
      "diskSizeGb", vm.getDiskSizeGb(),
      "osImage", vm.getOsImage(),
      "environmentPackages", environmentPackagesValue(vm.getEnvironmentPackages()),
      "status", actualStatus
    );
  }

  private List<String> environmentPackagesValue(final List<EnvironmentPackage> environmentPackages) {
    return environmentPackages.stream()
      .map(EnvironmentPackage::name)
      .sorted()
      .toList();
  }

  private String resolveActualStatus(final VirtualMachine vm) {
    if (!infrastructureCommandService.isEnabled()) {
      return vm.getStatus().name();
    }

    try {
      final String domState = infrastructureCommandService.resolveVmPowerState(vm.getName());
      return mapDomStateToVmStatus(domState).name();
    } catch (final Exception exception) {
      return vm.getStatus().name();
    }
  }

  private VmStatus mapDomStateToVmStatus(final String domStateRaw) {
    final String domState = domStateRaw == null ? "" : domStateRaw.trim().toLowerCase();

    if (domState.startsWith("running")) {
      return VmStatus.RUNNING;
    }
    if (domState.startsWith("shut off") || domState.startsWith("shutdown") || domState.startsWith("stopped")) {
      return VmStatus.STOPPED;
    }
    if (domState.startsWith("paused") || domState.startsWith("pmsuspended") || domState.startsWith("idle")) {
      return VmStatus.STOPPED;
    }

    return VmStatus.ERROR;
  }

  private List<DriftDifference> differences(
    final Map<String, Object> expectedState,
    final Map<String, Object> actualState
  ) {
    final List<DriftDifference> differences = new ArrayList<>();
    for (final String key : expectedState.keySet()) {
      final Object expected = expectedState.get(key);
      final Object actual = actualState.get(key);
      if (!String.valueOf(expected).equals(String.valueOf(actual))) {
        differences.add(new DriftDifference(key, String.valueOf(expected), String.valueOf(actual)));
      }
    }
    return differences;
  }

  private record DriftRecord(
    UUID id,
    UUID vmId,
    String vmName,
    DriftStatus status,
    Map<String, Object> expectedState,
    Map<String, Object> actualState,
    List<DriftDifference> differences,
    Instant checkedAt,
    Instant createdAt
  ) {
  }
}
