package itmo.backend.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import itmo.backend.model.dto.ErrorResponse;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

@Component
public class JwtAccessDeniedHandler implements AccessDeniedHandler {

  private final ObjectMapper objectMapper;

  public JwtAccessDeniedHandler(final ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Override
  public void handle(
    final HttpServletRequest request,
    final HttpServletResponse response,
    final AccessDeniedException accessDeniedException
  ) throws IOException, ServletException {
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setStatus(HttpServletResponse.SC_FORBIDDEN);

    final ErrorResponse errorResponse = new ErrorResponse(
      Instant.now(),
      HttpServletResponse.SC_FORBIDDEN,
      "Forbidden",
      "You do not have permission to access this resource",
      request.getRequestURI()
    );

    response.getWriter().write(objectMapper.writeValueAsString(errorResponse));
  }
}
