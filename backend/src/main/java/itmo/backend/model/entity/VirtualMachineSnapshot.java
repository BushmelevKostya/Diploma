package itmo.backend.model.entity;

import itmo.backend.model.dto.snapshot.SnapshotStatus;
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
@Table(name = "vm_snapshots")
public class VirtualMachineSnapshot {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false, length = 100)
  private String name;

  @Column(length = 500)
  private String description;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private SnapshotStatus status;

  @Column(nullable = false)
  private UUID vmId;

  @Column(nullable = false)
  private Long sizeBytes;

  @Column(nullable = false, length = 160)
  private String libvirtSnapshotName;

  @Column(nullable = false)
  private Boolean externalSnapshot;

  @Column(nullable = false)
  private Boolean diskOnly;

  @Column(nullable = false, updatable = false)
  private Instant createdAt;

  @Column(nullable = false)
  private Instant updatedAt;

  protected VirtualMachineSnapshot() {
  }

  public VirtualMachineSnapshot(
    final String name,
    final String description,
    final SnapshotStatus status,
    final UUID vmId,
    final Long sizeBytes,
    final String libvirtSnapshotName,
    final Boolean externalSnapshot,
    final Boolean diskOnly
  ) {
    this.name = name;
    this.description = description;
    this.status = status;
    this.vmId = vmId;
    this.sizeBytes = sizeBytes;
    this.libvirtSnapshotName = libvirtSnapshotName;
    this.externalSnapshot = externalSnapshot;
    this.diskOnly = diskOnly;
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

  public String getDescription() {
    return description;
  }

  public SnapshotStatus getStatus() {
    return status;
  }

  public UUID getVmId() {
    return vmId;
  }

  public Long getSizeBytes() {
    return sizeBytes;
  }

  public String getLibvirtSnapshotName() {
    return libvirtSnapshotName;
  }

  public Boolean getExternalSnapshot() {
    return externalSnapshot;
  }

  public Boolean getDiskOnly() {
    return diskOnly;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void markReady() {
    this.status = SnapshotStatus.READY;
  }

  public void markRestoring() {
    this.status = SnapshotStatus.RESTORING;
  }

  public void markFailed() {
    this.status = SnapshotStatus.FAILED;
  }
}
