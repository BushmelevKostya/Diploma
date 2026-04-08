package itmo.backend.config;

import java.util.Optional;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;
import itmo.backend.model.entity.User;
import itmo.backend.model.entity.UserRole;
import itmo.backend.model.repository.UserRepository;

@Configuration
public class DemoUserInitializer {

    @Bean
    public CommandLineRunner createDemoUser(final UserRepository userRepository, final PasswordEncoder passwordEncoder) {
        return args -> {
            final Optional<User> existingUser = userRepository.findByUsername("admin");
            if (existingUser.isPresent()) {
                return;
            }

            final User demoUser = new User(
                "admin",
                passwordEncoder.encode("admin123"),
                UserRole.ADMIN
            );
            userRepository.save(demoUser);
        };
    }
}
