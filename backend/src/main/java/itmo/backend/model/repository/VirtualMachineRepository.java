package itmo.backend.model.repository;

import itmo.backend.model.entity.VirtualMachine;
import itmo.backend.model.entity.VmStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VirtualMachineRepository extends JpaRepository<VirtualMachine, UUID> {

    Optional<VirtualMachine> findByName(String name);

    Page<VirtualMachine> findAllByStatus(VmStatus status, Pageable pageable);
}
