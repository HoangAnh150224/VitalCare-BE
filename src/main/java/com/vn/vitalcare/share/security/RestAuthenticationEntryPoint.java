package com.vn.vitalcare.share.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;

/**
 * Gives the security filter chain the same JSON error body the controllers use.
 *
 * <p>Spring Security answers a missing or invalid bearer token straight from
 * the filter chain, before any controller runs, and its default response has no
 * body at all. The data provider calls {@code response.json()} on every non-2xx
 * response, so an empty 401 surfaces in the admin UI as an unparseable-response
 * error rather than as "your session expired" — and the frontend never gets the
 * chance to refresh the token.
 *
 * <p>Rather than formatting a second error payload here, both handlers push the
 * exception back through the MVC exception resolver, so
 * {@link com.dth.frw.shared.exception.ApiExceptionHandler} stays the one place
 * that decides what an error looks like on the wire.
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final HandlerExceptionResolver resolver;

    public RestAuthenticationEntryPoint(
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        this.resolver = resolver;
    }

    /** No credentials, or credentials that did not verify. */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        resolver.resolveException(request, response, null, authException);
    }

    /** Valid credentials that do not carry the permission this endpoint wants. */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        resolver.resolveException(request, response, null, accessDeniedException);
    }
}
