package itmo.backend.model.repository;

import itmo.backend.model.entity.VirtualMachine;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VirtualMachineRepository extends JpaRepository<VirtualMachine, UUID> {

    Optional<VirtualMachine> findByName(String name);
}
