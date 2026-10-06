package com.backend.auth.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;
import java.time.Instant;

/**
 * Spring's default answer to "who are you?" is an empty 403. The apps parse JSON, so an
 * unauthenticated call gets the same error shape as every other failure instead.
 */
public class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final Logger log = LoggerFactory.getLogger(JsonAuthenticationEntryPoint.class);

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        if (log.isDebugEnabled()) {
            log.debug("refused {} {}: {}", request.getMethod(), request.getRequestURI(),
                    authException.getMessage());
        }
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().printf(
                "{\"status\":401,\"error\":\"Unauthorized\",\"message\":\"%s\",\"at\":\"%s\"}%n",
                "this device is not paired: send Authorization: Bearer <token>",
                Instant.now());
    }
}
