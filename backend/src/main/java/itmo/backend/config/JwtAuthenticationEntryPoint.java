package itmo.backend.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import itmo.backend.model.dto.ErrorResponse;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

@Component
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

  private final ObjectMapper objectMapper;

  public JwtAuthenticationEntryPoint(final ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Override
  public void commence(
    final HttpServletRequest request,
    final HttpServletResponse response,
    final AuthenticationException authException
  ) throws IOException, ServletException {
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);

    final ErrorResponse errorResponse = new ErrorResponse(
      Instant.now(),
      HttpServletResponse.SC_UNAUTHORIZED,
      "Unauthorized",
      "Authentication token was either missing or invalid",
      request.getRequestURI()
    );

    response.getWriter().write(objectMapper.writeValueAsString(errorResponse));
  }
}
