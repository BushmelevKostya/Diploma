package itmo.backend.services;

import java.util.UUID;
import org.springframework.stereotype.Service;
import itmo.backend.model.dto.auth.JwtResponse;
import itmo.backend.model.dto.auth.LoginRequest;
import itmo.backend.model.dto.auth.RefreshTokenRequest;

@Service
public class AuthService {

    public JwtResponse login(final LoginRequest request) {
        final String accessToken = "stub-access-" + request.username() + "-" + UUID.randomUUID();
        final String refreshToken = "stub-refresh-" + request.username() + "-" + UUID.randomUUID();
        return new JwtResponse(accessToken, refreshToken, "Bearer", 3600L);
    }

    public JwtResponse refresh(final RefreshTokenRequest request) {
        final String accessToken = "stub-access-from-refresh-" + UUID.randomUUID();
        return new JwtResponse(accessToken, request.refreshToken(), "Bearer", 3600L);
    }
}
