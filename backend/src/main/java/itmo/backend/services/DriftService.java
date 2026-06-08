package itmo.backend.services;

import itmo.backend.model.dto.drift.DriftDifference;
import itmo.backend.model.dto.drift.DriftReportResponse;
import itmo.backend.model.dto.drift.DriftStatus;
import itmo.backend.model.dto.drift.PageDriftReportResponse;
import itmo.backend.model.dto.drift.PageInfo;
import itmo.backend.model.dto.drift.VmConfigurationSnapshot;
import itmo.backend.model.dto.vm.EnvironmentPackage;
import itmo.backend.model.entity.VirtualMachine;
import itmo.backend.model.entity.VmStatus;
import itmo.backend.model.exceptions.ApiException;
import itmo.backend.model.repository.VirtualMachineRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class DriftService {

  private static final Logger log = LoggerFactory.getLogger(DriftService.class);

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
    log.info(
      "Drift report for VM {}: status={}, differences={}",
      vm.getName(),
      status,
      differences.size()
    );
    if (!differences.isEmpty()) {
      differences.forEach(difference ->
        log.info("Drift diff for VM {}: {} expected={} actual={}",
          vm.getName(), difference.field(), difference.expected(), difference.actual())
      );
    }
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
    final VmConfigurationSnapshot openTofuState = infrastructureCommandService.resolveOpenTofuVmConfiguration(vm.getName());

    if (openTofuState.found()) {
      return stateMap(
        firstNonBlank(openTofuState.name(), vm.getName()),
        firstNonBlank(openTofuState.hostname(), vm.getHostname()),
        firstNonNull(openTofuState.vcpu(), vm.getVcpu()),
        firstNonNull(openTofuState.memoryMb(), vm.getMemoryMb()),
        firstNonNull(openTofuState.diskSizeGb(), vm.getDiskSizeGb()),
        firstNonBlank(openTofuState.osImage(), vm.getOsImage()),
        environmentPackagesValue(vm.getEnvironmentPackages()),
        desiredStatus.name()
      );
    }

    return stateMap(
      vm.getName(),
      vm.getHostname(),
      vm.getVcpu(),
      vm.getMemoryMb(),
      vm.getDiskSizeGb(),
      vm.getOsImage(),
      environmentPackagesValue(vm.getEnvironmentPackages()),
      desiredStatus.name()
    );
  }

  private Map<String, Object> actualState(final VirtualMachine vm) {
    if (!infrastructureCommandService.isEnabled()) {
      return databaseActualState(vm);
    }

    final VmConfigurationSnapshot libvirtState = infrastructureCommandService.resolveLibvirtVmConfiguration(vm.getName());
    if (!libvirtState.found()) {
      log.warn(
        "Libvirt configuration not found for VM {}, using unavailable infrastructure marker instead of database fallback",
        vm.getName()
      );
      return unavailableInfrastructureState(vm);
    }

    final VmConfigurationSnapshot guestProfile = infrastructureCommandService.resolveGuestVmProfile(
      vm.getName(),
      firstNonBlank(libvirtState.osImage(), vm.getOsImage()),
      vm.getIpAddress()
    );

    final String hostname = guestProfile.found() && guestProfile.hostname() != null && !guestProfile.hostname().isBlank()
      ? guestProfile.hostname()
      : firstNonBlank(libvirtState.hostname(), vm.getHostname());

    final List<String> environmentPackages = guestProfile.found()
      ? guestProfile.environmentPackages()
      : List.of();

    return stateMap(
      firstNonBlank(libvirtState.name(), vm.getName()),
      hostname,
      firstNonNull(libvirtState.vcpu(), vm.getVcpu()),
      firstNonNull(libvirtState.memoryMb(), vm.getMemoryMb()),
      firstNonNull(libvirtState.diskSizeGb(), vm.getDiskSizeGb()),
      firstNonBlank(libvirtState.osImage(), vm.getOsImage()),
      environmentPackages,
      mapDomStateToVmStatus(libvirtState.status()).name()
    );
  }

  private Map<String, Object> databaseActualState(final VirtualMachine vm) {
    return stateMap(
      vm.getName(),
      vm.getHostname(),
      vm.getVcpu(),
      vm.getMemoryMb(),
      vm.getDiskSizeGb(),
      vm.getOsImage(),
      environmentPackagesValue(vm.getEnvironmentPackages()),
      vm.getStatus().name()
    );
  }

  private Map<String, Object> unavailableInfrastructureState(final VirtualMachine vm) {
    final VmConfigurationSnapshot guestProfile = infrastructureCommandService.resolveGuestVmProfile(
      vm.getName(),
      vm.getOsImage(),
      vm.getIpAddress()
    );

    final List<String> environmentPackages = guestProfile.found()
      ? guestProfile.environmentPackages()
      : List.of();

    final String hostname = guestProfile.found() && guestProfile.hostname() != null && !guestProfile.hostname().isBlank()
      ? guestProfile.hostname()
      : vm.getHostname();

    return stateMap(
      vm.getName(),
      hostname,
      0,
      0,
      0,
      "unknown",
      environmentPackages,
      VmStatus.ERROR.name()
    );
  }

  private Map<String, Object> stateMap(
    final String name,
    final String hostname,
    final int vcpu,
    final int memoryMb,
    final int diskSizeGb,
    final String osImage,
    final List<String> environmentPackages,
    final String status
  ) {
    final Map<String, Object> state = new LinkedHashMap<>();
    state.put("name", name);
    state.put("hostname", hostname);
    state.put("vcpu", vcpu);
    state.put("memoryMb", memoryMb);
    state.put("diskSizeGb", diskSizeGb);
    state.put("osImage", osImage);
    state.put("environmentPackages", environmentPackages);
    state.put("status", status);
    return state;
  }

  private List<String> environmentPackagesValue(final List<EnvironmentPackage> environmentPackages) {
    return environmentPackages.stream()
      .map(EnvironmentPackage::name)
      .sorted()
      .toList();
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
      if (!normalizeValue(expected).equals(normalizeValue(actual))) {
        differences.add(new DriftDifference(key, normalizeValue(expected), normalizeValue(actual)));
      }
    }
    return differences;
  }

  private String normalizeValue(final Object value) {
    if (value instanceof final List<?> list) {
      return list.stream()
        .map(String::valueOf)
        .sorted()
        .toList()
        .toString();
    }

    return String.valueOf(value);
  }

  private String firstNonBlank(final String preferred, final String fallback) {
    if (preferred != null && !preferred.isBlank()) {
      return preferred;
    }
    return fallback;
  }

  private int firstNonNull(final Integer preferred, final int fallback) {
    return preferred == null ? fallback : preferred;
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
