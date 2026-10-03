package com.backend.item;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every endpoint answers errors the same way: {status, error, message, at}.
 * Extending the Spring base handler keeps the standard statuses (404, 405, 400 on a bad body…)
 * instead of turning them into 500s.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex,
                                                             Object body,
                                                             HttpHeaders headers,
                                                             HttpStatusCode statusCode,
                                                             WebRequest request) {
        return new ResponseEntity<>(payload(statusCode, describe(ex)), headers, statusCode);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Object> onStatusException(ResponseStatusException e) {
        String message = e.getReason() == null ? e.getMessage() : e.getReason();
        return new ResponseEntity<>(payload(e.getStatusCode(), message), HttpHeaders.EMPTY, e.getStatusCode());
    }

    /**
     * Spring rejects the body before our own counter sees it, so translate it to the same 413.
     * Not annotated: ResponseEntityExceptionHandler already handles this type, and a second
     * mapping for it would be ambiguous.
     */
    @Override
    protected ResponseEntity<Object> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException ex,
                                                                          HttpHeaders headers,
                                                                          HttpStatusCode statusCode,
                                                                          WebRequest request) {
        return new ResponseEntity<>(payload(HttpStatus.PAYLOAD_TOO_LARGE, "file is larger than the 30 MB limit"),
                headers, HttpStatus.PAYLOAD_TOO_LARGE);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> onUnexpected(Exception e) {
        log.warn("request failed", e);
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return new ResponseEntity<>(payload(HttpStatus.INTERNAL_SERVER_ERROR, message),
                HttpHeaders.EMPTY, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private String describe(Exception e) {
        if (e instanceof MethodArgumentNotValidException invalid) {
            return invalid.getBindingResult().getFieldErrors().stream()
                    .findFirst()
                    .map(error -> error.getDefaultMessage())
                    .orElse("invalid request");
        }
        if (e instanceof ErrorResponse errorResponse && errorResponse.getBody() != null
                && errorResponse.getBody().getDetail() != null) {
            return errorResponse.getBody().getDetail();
        }
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private Map<String, Object> payload(HttpStatusCode statusCode, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", statusCode.value());
        payload.put("error", HttpStatus.resolve(statusCode.value()) == null
                ? statusCode.toString()
                : HttpStatus.resolve(statusCode.value()).getReasonPhrase());
        payload.put("message", message);
        payload.put("at", Instant.now());
        return payload;
    }
}
