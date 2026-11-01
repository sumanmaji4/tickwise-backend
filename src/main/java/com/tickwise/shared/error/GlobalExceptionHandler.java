package com.tickwise.shared.error;

import jakarta.validation.ConstraintViolationException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps every exception leaving a controller to an RFC 7807 {@code application/problem+json}
 * body. Spring MVC's own exceptions (unknown route, 405, unreadable body, ...) are handled by
 * the superclass and enriched here. Internal details are logged, never returned.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handleApi(ApiException ex) {
        ProblemDetail problem = Problems.of(ex.status(), ex.type(), ex.title(), ex.getMessage());
        if (ex instanceof TooManyRequestsException limited) {
            return ResponseEntity.status(problem.getStatus())
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1, limited.retryAfter().toSeconds())))
                    .body(problem);
        }
        return respond(problem);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException ex) {
        List<Map<String, String>> errors = ex.getConstraintViolations().stream()
                .map(v -> Map.of("field", v.getPropertyPath().toString(), "message", v.getMessage()))
                .sorted(Comparator.comparing(m -> m.get("field")))
                .toList();
        return respond(validationProblem(errors));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> handleOptimisticLock(OptimisticLockingFailureException ex) {
        return respond(Problems.of(HttpStatus.CONFLICT, ProblemTypes.CONCURRENT_MODIFICATION,
                "Concurrent modification", "The resource was modified by someone else; reload and retry"));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> handleIntegrity(DataIntegrityViolationException ex) {
        // Usually a race past an application-level uniqueness check. The constraint name is internal.
        log.info("Data integrity violation: {}", ex.getMostSpecificCause().getMessage());
        return respond(Problems.of(HttpStatus.CONFLICT, ProblemTypes.CONFLICT,
                "Conflict", "The request conflicts with existing data"));
    }

    // Method-security failures surface here rather than in the security filter chain.
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
        return respond(Problems.of(HttpStatus.FORBIDDEN, ProblemTypes.FORBIDDEN,
                "Forbidden", "You do not have permission to perform this action"));
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException ex) {
        return respond(Problems.of(HttpStatus.UNAUTHORIZED, ProblemTypes.UNAUTHORIZED,
                "Unauthorized", "Authentication is required"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return respond(Problems.of(HttpStatus.INTERNAL_SERVER_ERROR, ProblemTypes.INTERNAL,
                "Internal error", "An unexpected error occurred"));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .sorted(Comparator.comparing(FieldError::getField))
                .map(e -> Map.of("field", e.getField(), "message", String.valueOf(e.getDefaultMessage())))
                .toList();
        return ResponseEntity.badRequest().body(validationProblem(errors));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, @Nullable Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, status, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            Problems.enrich(problem);
        }
        return response;
    }

    private static ProblemDetail validationProblem(List<Map<String, String>> errors) {
        ProblemDetail problem = Problems.of(HttpStatus.BAD_REQUEST, ProblemTypes.VALIDATION,
                "Validation failed", "One or more fields are invalid");
        problem.setProperty("errors", errors);
        return problem;
    }

    private static ResponseEntity<ProblemDetail> respond(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }
}
