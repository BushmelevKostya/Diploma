package itmo.backend.services;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.security.crypto.password.PasswordEncoder;
import itmo.backend.model.dto.auth.JwtResponse;
import itmo.backend.model.dto.auth.LoginRequest;
import itmo.backend.model.dto.auth.RefreshTokenRequest;
import itmo.backend.model.entity.User;
import itmo.backend.model.repository.UserRepository;
import itmo.backend.model.exceptions.ApiException;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthService(final UserRepository userRepository, final PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public JwtResponse login(final LoginRequest request) {
        final User user = userRepository.findByUsername(request.username())
            .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Invalid username or password"));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
        }

        final String accessToken = "stub-access-" + request.username() + "-" + UUID.randomUUID();
        final String refreshToken = "stub-refresh-" + request.username() + "-" + UUID.randomUUID();
        return new JwtResponse(accessToken, refreshToken, "Bearer", 3600L);
    }

    public JwtResponse refresh(final RefreshTokenRequest request) {
        final String accessToken = "stub-access-from-refresh-" + UUID.randomUUID();
        return new JwtResponse(accessToken, request.refreshToken(), "Bearer", 3600L);
    }
}
