package itmo.backend.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "virtual_machines")
public class VirtualMachine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Column(length = 100)
    private String hostname;

    @Column(length = 64)
    private String ipAddress;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VmStatus status;

    @Column(nullable = false)
    private Integer vcpu;

    @Column(nullable = false)
    private Integer memoryMb;

    @Column(nullable = false)
    private Integer diskSizeGb;

    @Column(nullable = false)
    private String osImage;

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
        final VmStatus status,
        final Integer vcpu,
        final Integer memoryMb,
        final Integer diskSizeGb,
        final String osImage,
        final UUID createdBy
    ) {
        this.name = name;
        this.hostname = hostname;
        this.ipAddress = ipAddress;
        this.status = status;
        this.vcpu = vcpu;
        this.memoryMb = memoryMb;
        this.diskSizeGb = diskSizeGb;
        this.osImage = osImage;
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
    }

    public void stop() {
        this.status = VmStatus.STOPPED;
    }
}
