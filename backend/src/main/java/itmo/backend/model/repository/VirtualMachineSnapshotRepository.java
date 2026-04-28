package itmo.backend.model.repository;

import itmo.backend.model.entity.VirtualMachineSnapshot;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VirtualMachineSnapshotRepository extends JpaRepository<VirtualMachineSnapshot, UUID> {

  List<VirtualMachineSnapshot> findAllByVmIdOrderByCreatedAtDesc(UUID vmId);

  Optional<VirtualMachineSnapshot> findByIdAndVmId(UUID id, UUID vmId);
}
