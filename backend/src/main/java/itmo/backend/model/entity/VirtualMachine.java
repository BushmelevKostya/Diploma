package itmo.backend.model.entity;

import itmo.backend.model.dto.vm.EnvironmentPackage;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "virtual_machines")
public class VirtualMachine {
    private static final int STATUS_MESSAGE_LIMIT = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Column(length = 100)
    private String hostname;

    @Column(length = 64)
    private String ipAddress;

    @Column(length = 500)
    private String statusMessage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VmStatus status;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private VmStatus desiredStatus;

    @Column(nullable = false)
    private Integer vcpu;

    @Column(name = "cpu_cores", nullable = false, columnDefinition = "INTEGER DEFAULT 1", insertable = false, updatable = false)
    private Integer cpuCores;

    @Column(nullable = false)
    private Integer memoryMb;

    @Column(nullable = false)
    private Integer diskSizeGb;

    @Column(nullable = false)
    private String osImage;

    @ElementCollection(fetch = FetchType.EAGER, targetClass = EnvironmentPackage.class)
    @CollectionTable(name = "virtual_machine_environment_packages", joinColumns = @JoinColumn(name = "vm_id"))
    @Column(name = "environment_package", nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    private Set<EnvironmentPackage> environmentPackages = new LinkedHashSet<>();

    @Column
    private UUID createdBy;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected VirtualMachine() {
    }

    public VirtualMachine(
        final String name,
        final String hostname,
        final String ipAddress,
        final String statusMessage,
        final VmStatus status,
        final Integer vcpu,
        final Integer memoryMb,
        final Integer diskSizeGb,
        final String osImage,
        final Set<EnvironmentPackage> environmentPackages,
        final UUID createdBy
    ) {
        this.name = name;
        this.hostname = hostname;
        this.ipAddress = ipAddress;
        this.statusMessage = statusMessage;
        this.status = status;
        this.desiredStatus = VmStatus.RUNNING;
        this.vcpu = vcpu;
        this.memoryMb = memoryMb;
        this.diskSizeGb = diskSizeGb;
        this.osImage = osImage;
        this.environmentPackages = environmentPackages == null ? new LinkedHashSet<>() : new LinkedHashSet<>(environmentPackages);
        this.createdBy = createdBy;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getHostname() {
        return hostname;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public VmStatus getStatus() {
        return status;
    }

    public VmStatus getDesiredStatus() {
        return desiredStatus;
    }

    public String getStatusMessage() {
        return statusMessage;
    }

    public Integer getVcpu() {
        return vcpu;
    }

    public Integer getMemoryMb() {
        return memoryMb;
    }

    public Integer getDiskSizeGb() {
        return diskSizeGb;
    }

    public String getOsImage() {
        return osImage;
    }

    public List<EnvironmentPackage> getEnvironmentPackages() {
        return environmentPackages.stream().toList();
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void update(final String hostname, final Integer vcpu, final Integer memoryMb) {
        if (hostname != null) {
            this.hostname = hostname;
        }
        if (vcpu != null) {
            this.vcpu = vcpu;
        }
        if (memoryMb != null) {
            this.memoryMb = memoryMb;
        }
    }

    public void start() {
        this.status = VmStatus.RUNNING;
        this.statusMessage = normalizeStatusMessage("VM is running");
    }

    public void stop() {
        this.status = VmStatus.STOPPED;
        this.statusMessage = normalizeStatusMessage("VM is stopped");
    }

    public void markDesiredStatus(final VmStatus desiredStatus) {
        this.desiredStatus = desiredStatus;
    }

    public void markCreating(final String statusMessage) {
        this.status = VmStatus.CREATING;
        this.statusMessage = normalizeStatusMessage(statusMessage);
    }

    public void markRunning(final String ipAddress, final String statusMessage) {
        this.ipAddress = ipAddress;
        this.status = VmStatus.RUNNING;
        if (this.desiredStatus == null) {
            this.desiredStatus = VmStatus.RUNNING;
        }
        this.statusMessage = normalizeStatusMessage(statusMessage);
    }

    public void markIpAddress(final String ipAddress, final String statusMessage) {
        this.ipAddress = ipAddress;
        this.statusMessage = normalizeStatusMessage(statusMessage);
    }

    public void markError(final String statusMessage) {
        this.status = VmStatus.ERROR;
        this.statusMessage = normalizeStatusMessage(statusMessage);
    }

    private String normalizeStatusMessage(final String statusMessage) {
        if (statusMessage == null || statusMessage.isBlank()) {
            return null;
        }

        final String normalized = statusMessage
            .replace("\u0000", "")
            .replace('\uFEFF', ' ')
            .replace('\r', ' ')
            .replace('\n', ' ')
            .replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", " ")
            .replaceAll("\\s+", " ")
            .trim();

        if (normalized.length() <= STATUS_MESSAGE_LIMIT) {
            return normalized;
        }

        return normalized.substring(0, STATUS_MESSAGE_LIMIT - 3) + "...";
    }
}
