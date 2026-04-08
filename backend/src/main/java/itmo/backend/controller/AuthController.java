package itmo.backend.controller;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import itmo.backend.model.dto.auth.JwtResponse;
import itmo.backend.model.dto.auth.LoginRequest;
import itmo.backend.model.dto.auth.RefreshTokenRequest;
import itmo.backend.model.dto.auth.RegisterRequest;
import itmo.backend.services.AuthService;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(final AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public JwtResponse login(@Valid @RequestBody final LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/refresh")
    public JwtResponse refresh(@Valid @RequestBody final RefreshTokenRequest request) {
        return authService.refresh(request);
    }

    @PostMapping("/register")
    public JwtResponse register(@Valid @RequestBody final RegisterRequest request) {
        return authService.register(request);
    }
}
