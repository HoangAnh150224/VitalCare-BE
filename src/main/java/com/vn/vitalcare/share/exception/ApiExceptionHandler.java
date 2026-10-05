package com.vn.vitalcare.share.exception;

import com.vn.vitalcare.share.security.rowlevel.PolicyValidationException;
import com.vn.vitalcare.share.security.rowlevel.RowLevelViolationException;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns the exceptions the controllers and the security filter chain can raise
 * into a JSON body, so no error response ever comes back with an empty payload.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(ResourceNotFoundException e) {
        return body(HttpStatus.NOT_FOUND, e.getMessage(), Map.of());
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(ConflictException e) {
        return body(HttpStatus.CONFLICT, e.getMessage(), Map.of());
    }

    /**
     * Wrong phone number or password.
     *
     * <p>Deliberately the same message whichever half was wrong: saying which
     * one turns the login form into a way to find out whether an account
     * exists.
     */
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Map<String, Object>> handleBadCredentials(BadCredentialsException e) {
        return body(HttpStatus.UNAUTHORIZED, e.getMessage(), Map.of());
    }

    /** No access token, or one that is expired, tampered with or not ours. */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String, Object>> handleUnauthenticated(AuthenticationException e) {
        return body(HttpStatus.UNAUTHORIZED, "Authentication is required to access this resource", Map.of());
    }

    /**
     * A valid token without the permission the endpoint asks for. Distinct from
     * a 401 on purpose: the client must not try to refresh a token that is
     * working exactly as intended.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException e) {
        return body(HttpStatus.FORBIDDEN, "You do not have permission to perform this action", Map.of());
    }

    /** Field-level bean validation failures on a {@code @Valid} request body. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidBody(MethodArgumentNotValidException e) {
        Map<String, Object> errors = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(error ->
                errors.putIfAbsent(error.getField(), error.getDefaultMessage()));

        return body(HttpStatus.UNPROCESSABLE_CONTENT, "Validation failed", errors);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraintViolation(ConstraintViolationException e) {
        return body(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage(), Map.of());
    }

    /**
     * A write whose resulting state falls outside the caller's data scope.
     *
     * <p>422, the same status a failed bean validation gets: the request was
     * understood and the row does exist as far as this caller is concerned.
     * What is refused is the state it would leave behind.
     */
    @ExceptionHandler(RowLevelViolationException.class)
    public ResponseEntity<Map<String, Object>> handleRowLevel(RowLevelViolationException e) {
        Map<String, Object> errors = e.field() == null
                ? Map.of()
                : Map.of(e.field(), "outside your data scope");
        return body(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage(), errors);
    }

    /**
     * A row-level policy that cannot be compiled — an unknown field, an operator
     * the type does not support, a literal that will not coerce. Raised only on
     * the path that saves a policy; the same failure at load time is quarantined
     * instead.
     */
    @ExceptionHandler(PolicyValidationException.class)
    public ResponseEntity<Map<String, Object>> handlePolicyValidation(PolicyValidationException e) {
        Map<String, Object> errors = e.node() == null
                ? Map.of()
                : Map.of("scope", e.node());
        return body(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage(), errors);
    }

    /** A foreign key declared {@code ON DELETE RESTRICT} refusing the write. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleIntegrityViolation(DataIntegrityViolationException e) {
        return body(HttpStatus.CONFLICT, "This record is still referenced by other records", Map.of());
    }

    /** Covers unparseable enum / id values a request DTO rejects. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        return body(HttpStatus.BAD_REQUEST, e.getMessage(), Map.of());
    }

    private ResponseEntity<Map<String, Object>> body(HttpStatus status, String message, Map<String, Object> errors) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("timestamp", Instant.now().toString());
        payload.put("status", status.value());
        payload.put("error", status.getReasonPhrase());
        payload.put("message", message);
        if (!errors.isEmpty()) {
            payload.put("errors", errors);
        }
        return ResponseEntity.status(status).body(payload);
    }
}
