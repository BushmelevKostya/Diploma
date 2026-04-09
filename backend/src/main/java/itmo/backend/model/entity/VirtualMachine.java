package itmo.backend.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
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

    @Column(length = 64)
    private String ipAddress;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VmStatus status;

    @Column(nullable = false)
    private Integer cpuCores;

    @Column(nullable = false)
    private Integer memoryMb;

    @Column(nullable = false)
    private Integer diskGb;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected VirtualMachine() {
    }

    public VirtualMachine(
        final String name,
        final String ipAddress,
        final VmStatus status,
        final Integer cpuCores,
        final Integer memoryMb,
        final Integer diskGb
    ) {
        this.name = name;
        this.ipAddress = ipAddress;
        this.status = status;
        this.cpuCores = cpuCores;
        this.memoryMb = memoryMb;
        this.diskGb = diskGb;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public VmStatus getStatus() {
        return status;
    }

    public Integer getCpuCores() {
        return cpuCores;
    }

    public Integer getMemoryMb() {
        return memoryMb;
    }

    public Integer getDiskGb() {
        return diskGb;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
