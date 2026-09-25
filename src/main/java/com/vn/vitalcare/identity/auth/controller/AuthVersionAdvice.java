package com.vn.vitalcare.identity.auth.controller;

import com.vn.vitalcare.identity.auth.service.AuthorizationService;
import com.vn.vitalcare.share.security.CurrentUser;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Stamps every answer with what the caller may currently do.
 *
 * <p>The browser holds a cached copy of its permissions so that hiding a button
 * is a local decision rather than a request. This rides along on traffic the
 * application is already producing: a short header on every response, which the
 * client compares against the one it holds.
 *
 * <h2>Why an advice and not an interceptor</h2>
 *
 * <p>A {@code HandlerInterceptor} would have to write the header in
 * {@code preHandle}, which runs <em>before</em> the handler — so the one
 * response that matters most, the answer to the request that just changed
 * somebody's roles, would carry the version from before the change.
 * {@link ResponseBodyAdvice} runs after the handler and before the body is
 * written, which is the only window where the value is both current and still
 * settable.
 */
@RestControllerAdvice
public class AuthVersionAdvice implements ResponseBodyAdvice<Object> {

    /**
     * Must also be listed in {@code WebMvcConfig}'s
     * {@code Access-Control-Expose-Headers}. It is not CORS-safelisted, so
     * without that the browser strips it and this whole mechanism does nothing
     * — with no error anywhere to say so.
     */
    public static final String HEADER = "X-Auth-Version";

    private final AuthorizationService authorizationService;

    public AuthVersionAdvice(AuthorizationService authorizationService) {
        this.authorizationService = authorizationService;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body,
                                  MethodParameter returnType,
                                  MediaType contentType,
                                  Class<? extends HttpMessageConverter<?>> converterType,
                                  ServerHttpRequest request,
                                  ServerHttpResponse response) {
        // Absent on the open endpoints — signing in has nothing to compare yet.
        CurrentUser.id().ifPresent(id ->
                response.getHeaders().set(HEADER, authorizationService.versionOf(id)));

        // Returned untouched: this advice exists for the header alone.
        return body;
    }
}
