package itmo.backend.model.exceptions;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import itmo.backend.model.dto.ErrorResponse;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(final ApiException ex, final HttpServletRequest request) {
        final HttpStatus status = ex.getStatus();
        return ResponseEntity.status(status).body(
            new ErrorResponse(Instant.now(), status.value(), status.getReasonPhrase(), ex.getMessage(), request.getRequestURI())
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(final MethodArgumentNotValidException ex, final HttpServletRequest request) {
        final HttpStatus status = HttpStatus.BAD_REQUEST;
        final String message = ex.getBindingResult().getFieldErrors().stream()
            .findFirst()
            .map(err -> err.getField() + ": " + err.getDefaultMessage())
            .orElse("Validation failed");

        return ResponseEntity.status(status).body(
            new ErrorResponse(Instant.now(), status.value(), status.getReasonPhrase(), message, request.getRequestURI())
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(final Exception ex, final HttpServletRequest request) {
        final HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        return ResponseEntity.status(status).body(
            new ErrorResponse(Instant.now(), status.value(), status.getReasonPhrase(), "Unexpected server error", request.getRequestURI())
        );
    }
}
