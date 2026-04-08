package itmo.backend.services;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

  private final SecretKey signingKey;
  private final long accessExpirationSeconds;
  private final long refreshExpirationSeconds;

  public JwtService(
    @Value("${auth.jwt.secret}") final String secret,
    @Value("${auth.jwt.access-expiration-seconds}") final long accessExpirationSeconds,
    @Value("${auth.jwt.refresh-expiration-seconds}") final long refreshExpirationSeconds
  ) {
    this.signingKey = resolveSigningKey(secret);
    this.accessExpirationSeconds = accessExpirationSeconds;
    this.refreshExpirationSeconds = refreshExpirationSeconds;
  }

  public String generateAccessToken(final String username, final String role) {
    return buildToken(username, "access", accessExpirationSeconds, Map.of("role", role));
  }

  public String generateRefreshToken(final String username) {
    return buildToken(username, "refresh", refreshExpirationSeconds, Map.of());
  }

  public String extractUsername(final String token) {
    final Claims claims = parseClaims(token);
    return claims.getSubject();
  }

  public String extractTokenType(final String token) {
    final Claims claims = parseClaims(token);
    return claims.get("type", String.class);
  }

  public boolean isTokenValid(final String token) {
    try {
      parseClaims(token);
      return true;
    } catch (final Exception ignored) {
      return false;
    }
  }

  private String buildToken(final String username, final String type, final long expirationSeconds, final Map<String, Object> extraClaims) {
    final Instant now = Instant.now();
    final Instant expiresAt = now.plusSeconds(expirationSeconds);

    return Jwts.builder()
      .subject(username)
      .claims(extraClaims)
      .claim("type", type)
      .issuedAt(Date.from(now))
      .expiration(Date.from(expiresAt))
      .signWith(signingKey, SignatureAlgorithm.HS256)
      .compact();
  }

  private Claims parseClaims(final String token) {
    return Jwts.parser()
      .verifyWith(signingKey)
      .build()
      .parseSignedClaims(token)
      .getPayload();
  }

  private SecretKey resolveSigningKey(final String secret) {
    try {
      return Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
    } catch (final Exception ignored) {
      final byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
      return Keys.hmacShaKeyFor(keyBytes);
    }
  }
}
