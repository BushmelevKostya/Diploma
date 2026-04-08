package itmo.backend.services;

import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class HealthService {

    public Map<String, String> details() {
        return Map.of("database", "unknown", "libvirt", "unknown");
    }
}
