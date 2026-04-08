package itmo.backend.model.dto.auth;

public record JwtResponse(
    String accessToken,
    String refreshToken,
    String tokenType,
    long expiresIn
) {
}
