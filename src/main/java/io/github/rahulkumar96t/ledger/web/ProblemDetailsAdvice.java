package io.github.rahulkumar96t.ledger.web;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import io.github.rahulkumar96t.ledger.domain.DuplicateAccountCodeException;
import io.github.rahulkumar96t.ledger.domain.LedgerRuleViolation;
import io.github.rahulkumar96t.ledger.domain.NotFoundException;
import io.github.rahulkumar96t.ledger.service.ConcurrencyConflictException;
import io.github.rahulkumar96t.ledger.service.IdempotencyException;
import io.github.rahulkumar96t.ledger.service.InvalidCursorException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps every error to an {@code application/problem+json} body (RFC 9457, which obsoletes RFC 7807). The
 * {@code type} is a relative URI {@code /problems/<slug>} that clients can switch on; extension members carry
 * machine-readable details such as {@code errors}, {@code imbalances} or {@code retryable}.
 */
@RestControllerAdvice
public class ProblemDetailsAdvice extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailsAdvice.class);

    @ExceptionHandler(LedgerRuleViolation.class)
    ResponseEntity<ProblemDetail> ruleViolation(LedgerRuleViolation e, HttpServletRequest request) {
        ProblemDetail problem = problem(
                HttpStatus.UNPROCESSABLE_ENTITY, e.rule().slug(), e.rule().title(), e.getMessage(), request);
        e.details().forEach(problem::setProperty);
        return respond(problem);
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ProblemDetail> notFound(NotFoundException e, HttpServletRequest request) {
        return respond(
                problem(HttpStatus.NOT_FOUND, "not-found", e.resource() + " not found", e.getMessage(), request));
    }

    @ExceptionHandler(DuplicateAccountCodeException.class)
    ResponseEntity<ProblemDetail> duplicateCode(DuplicateAccountCodeException e, HttpServletRequest request) {
        ProblemDetail problem = problem(
                HttpStatus.CONFLICT, "duplicate-account-code", "Duplicate account code", e.getMessage(), request);
        problem.setProperty("code", e.code());
        return respond(problem);
    }

    @ExceptionHandler(ConcurrencyConflictException.class)
    ResponseEntity<ProblemDetail> concurrency(ConcurrencyConflictException e, HttpServletRequest request) {
        ProblemDetail problem = problem(
                HttpStatus.CONFLICT, "concurrent-modification", "Concurrent modification", e.getMessage(), request);
        problem.setProperty("retryable", true);
        problem.setProperty("attempts", e.attempts());
        return respond(problem);
    }

    @ExceptionHandler(IdempotencyException.class)
    ResponseEntity<ProblemDetail> idempotency(IdempotencyException e, HttpServletRequest request) {
        return switch (e.kind()) {
            case INVALID_KEY ->
                respond(problem(
                        HttpStatus.BAD_REQUEST,
                        "invalid-idempotency-key",
                        "Invalid Idempotency-Key",
                        e.getMessage(),
                        request));
            case KEY_REUSED ->
                respond(problem(
                        HttpStatus.CONFLICT,
                        "idempotency-key-reused",
                        "Idempotency-Key reused with a different request",
                        e.getMessage(),
                        request));
            case IN_PROGRESS -> {
                ProblemDetail problem = problem(
                        HttpStatus.CONFLICT,
                        "idempotency-key-in-progress",
                        "Request with this Idempotency-Key in progress",
                        e.getMessage(),
                        request);
                problem.setProperty("retryable", true);
                yield ResponseEntity.status(HttpStatus.CONFLICT)
                        .header(HttpHeaders.RETRY_AFTER, "1")
                        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body(problem);
            }
        };
    }

    @ExceptionHandler(InvalidCursorException.class)
    ResponseEntity<ProblemDetail> invalidCursor(InvalidCursorException e, HttpServletRequest request) {
        return respond(problem(HttpStatus.BAD_REQUEST, "invalid-cursor", "Invalid cursor", e.getMessage(), request));
    }

    /** Last resort: never leak internals, but log them. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e, HttpServletRequest request) {
        log.error("Unhandled error on {} {}", request.getMethod(), request.getRequestURI(), e);
        return respond(problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "internal-error",
                "Internal error",
                "The request could not be completed",
                request));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = new ArrayList<>();
        ex.getBindingResult().getFieldErrors().forEach(error -> errors.add(fieldError(error)));
        ex.getBindingResult()
                .getGlobalErrors()
                .forEach(error -> errors.add(error(error.getObjectName(), error.getDefaultMessage())));
        return handleExceptionInternal(ex, validationProblem(ex.getBody(), errors), headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = new ArrayList<>();
        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            if (result instanceof ParameterErrors parameterErrors) {
                parameterErrors.getFieldErrors().forEach(error -> errors.add(fieldError(error)));
            } else {
                String name = result.getMethodParameter().getParameterName();
                result.getResolvableErrors().forEach(error -> errors.add(error(name, error.getDefaultMessage())));
            }
        }
        return handleExceptionInternal(ex, validationProblem(ex.getBody(), errors), headers, status, request);
    }

    /**
     * Unparseable JSON, wrong types, floats or strings where an integer amount is expected, unknown fields. The
     * offending field path is reported without echoing Jackson's internal message.
     */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(URI.create("/problems/malformed-request"));
        problem.setTitle("Malformed request body");
        problem.setDetail("The request body is not valid JSON for this endpoint");
        if (ex.getCause() instanceof JsonMappingException mapping
                && !mapping.getPath().isEmpty()) {
            String field = fieldPath(mapping.getPath());
            problem.setProperty("field", field);
            if (mapping instanceof UnrecognizedPropertyException) {
                problem.setDetail("Unknown field '" + field + "'");
            } else if (field.endsWith("amount")) {
                problem.setDetail("Invalid value for '" + field + "': amounts are integers in minor units");
            } else {
                problem.setDetail("Invalid value for '" + field + "'");
            }
        }
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    private static String fieldPath(List<JsonMappingException.Reference> path) {
        StringBuilder field = new StringBuilder();
        for (JsonMappingException.Reference ref : path) {
            if (ref.getFieldName() != null) {
                field.append(field.isEmpty() ? "" : ".").append(ref.getFieldName());
            } else {
                field.append('[').append(ref.getIndex()).append(']');
            }
        }
        return field.toString();
    }

    /** Fills in {@code instance} for the framework-generated problems (malformed JSON, type mismatches, 404...). */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem
                && problem.getInstance() == null
                && request instanceof ServletWebRequest servlet) {
            problem.setInstance(URI.create(servlet.getRequest().getRequestURI()));
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }

    private static ProblemDetail validationProblem(ProblemDetail problem, List<Map<String, String>> errors) {
        problem.setType(URI.create("/problems/validation-failed"));
        problem.setTitle("Validation failed");
        problem.setDetail("The request has " + errors.size() + " invalid field(s)");
        problem.setProperty("errors", errors);
        return problem;
    }

    private static Map<String, String> fieldError(FieldError error) {
        return error(error.getField(), error.getDefaultMessage());
    }

    private static Map<String, String> error(String field, String message) {
        return Map.of(
                "field",
                Objects.requireNonNullElse(field, ""),
                "message",
                Objects.requireNonNullElse(message, "invalid"));
    }

    private static ProblemDetail problem(
            HttpStatus status, String slug, String title, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("/problems/" + slug));
        problem.setTitle(title);
        problem.setInstance(URI.create(request.getRequestURI()));
        return problem;
    }

    private static ResponseEntity<ProblemDetail> respond(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }
}
