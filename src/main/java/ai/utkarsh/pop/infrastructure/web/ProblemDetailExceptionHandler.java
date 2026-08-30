package ai.utkarsh.pop.infrastructure.web;

import ai.utkarsh.pop.domain.port.in.GetInvestigationUseCase.InvestigationNotFoundException;
import ai.utkarsh.pop.domain.port.out.DiagnosisEnginePort.DiagnosisFailedException;
import ai.utkarsh.pop.infrastructure.investigator.postgres.UnsafeSqlException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.time.Instant;
import java.util.List;

/**
 * RFC 9457 error responses.
 *
 * <p>One advice for the whole application — controllers contain no try/catch. Extending
 * {@link ResponseEntityExceptionHandler} keeps Spring's own exceptions (unreadable body, wrong
 * method, failed validation) in the same Problem Details shape as the domain's.
 */
@Slf4j
@RestControllerAdvice
class ProblemDetailExceptionHandler extends ResponseEntityExceptionHandler {

    private static final String ERROR_BASE = "https://pop.utkarsh.ai/errors/";

    @ExceptionHandler(InvestigationNotFoundException.class)
    ProblemDetail handleNotFound(InvestigationNotFoundException ex, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "Investigation Not Found", ex.getMessage(),
                "investigation-not-found", "INVESTIGATION_NOT_FOUND", request.getRequestURI());
    }

    /**
     * A refused statement is a client error, not a server fault — the caller asked for
     * something that is not permitted, and the message says exactly why.
     */
    @ExceptionHandler(UnsafeSqlException.class)
    ProblemDetail handleUnsafeSql(UnsafeSqlException ex, HttpServletRequest request) {
        log.warn("Rejected unsafe SQL from {}: {}", request.getRequestURI(), ex.getMessage());
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Unsafe SQL Rejected", ex.getMessage(),
                "unsafe-sql", "UNSAFE_SQL", request.getRequestURI());
    }

    @ExceptionHandler(DiagnosisFailedException.class)
    ProblemDetail handleDiagnosisFailure(DiagnosisFailedException ex, HttpServletRequest request) {
        log.error("Diagnosis engine failure", ex);
        return problem(HttpStatus.BAD_GATEWAY, "Diagnosis Failed",
                "The diagnosis engine could not complete this investigation.",
                "diagnosis-failed", "DIAGNOSIS_FAILED", request.getRequestURI());
    }

    /** Domain invariant violations — a bad service name, an impossible time window. */
    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid Request", ex.getMessage(),
                "invalid-request", "INVALID_REQUEST", request.getRequestURI());
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail handleIllegalState(IllegalStateException ex, HttpServletRequest request) {
        log.warn("Illegal state: {}", ex.getMessage());
        return problem(HttpStatus.CONFLICT, "Invalid State Transition", ex.getMessage(),
                "invalid-state", "INVALID_STATE", request.getRequestURI());
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex, HttpServletRequest request) {
        // Log the detail, return a generic message — internals must not leak to the caller.
        log.error("Unhandled exception at {}", request.getRequestURI(), ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Error",
                "An unexpected error occurred.", "internal", "INTERNAL_ERROR", request.getRequestURI());
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {

        List<String> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .toList();

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Request validation failed");
        problem.setType(URI.create(ERROR_BASE + "validation"));
        problem.setTitle("Validation Failed");
        problem.setProperty("errorCode", "VALIDATION_FAILED");
        problem.setProperty("violations", violations);
        problem.setProperty("timestamp", Instant.now());

        return ResponseEntity.badRequest().body(problem);
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail,
                                         String typeSuffix, String errorCode, String instance) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(ERROR_BASE + typeSuffix));
        problem.setTitle(title);
        problem.setInstance(URI.create(instance));
        problem.setProperty("errorCode", errorCode);
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }
}
