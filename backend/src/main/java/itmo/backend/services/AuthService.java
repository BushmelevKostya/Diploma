package itmo.backend.services;

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
    private final JwtService jwtService;

    public AuthService(final UserRepository userRepository, final PasswordEncoder passwordEncoder, final JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public JwtResponse login(final LoginRequest request) {
        final User user = userRepository.findByUsername(request.username())
            .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Invalid username or password"));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
        }

        final String accessToken = jwtService.generateAccessToken(user.getUsername(), user.getRole().name());
        final String refreshToken = jwtService.generateRefreshToken(user.getUsername());
        return new JwtResponse(accessToken, refreshToken, "Bearer", 3600L);
    }

    public JwtResponse refresh(final RefreshTokenRequest request) {
        final String refreshToken = request.refreshToken();
        if (!jwtService.isTokenValid(refreshToken)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid refresh token");
        }

        final String tokenType = jwtService.extractTokenType(refreshToken);
        if (!"refresh".equals(tokenType)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Token is not a refresh token");
        }

        final String username = jwtService.extractUsername(refreshToken);
        final User user = userRepository.findByUsername(username)
            .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "User not found for token"));

        final String accessToken = jwtService.generateAccessToken(user.getUsername(), user.getRole().name());
        return new JwtResponse(accessToken, refreshToken, "Bearer", 3600L);
    }
}
