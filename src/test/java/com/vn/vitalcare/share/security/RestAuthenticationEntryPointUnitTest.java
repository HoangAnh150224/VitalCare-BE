package com.vn.vitalcare.share.security;

import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.servlet.HandlerExceptionResolver;

/** Report-5.1 BE_Infra: RestAuthenticationEntryPoint gives 401/403 from the filter chain the controllers' JSON body. */
class RestAuthenticationEntryPointUnitTest {

    private final HandlerExceptionResolver resolver = mock(HandlerExceptionResolver.class);
    private final RestAuthenticationEntryPoint entryPoint = new RestAuthenticationEntryPoint(resolver);
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @Test
    @DisplayName("No or bad credentials: the exception goes through the MVC exception resolver")
    void ep01_unauthenticated() throws Exception {
        BadCredentialsException e = new BadCredentialsException("bad token");
        entryPoint.commence(request, response, e);
        verify(resolver).resolveException(org.mockito.ArgumentMatchers.same(request),
                org.mockito.ArgumentMatchers.same(response), isNull(), org.mockito.ArgumentMatchers.same(e));
    }

    @Test
    @DisplayName("Signed in without the permission: the access-denied exception goes through the same resolver")
    void ep02_accessDenied() throws Exception {
        AccessDeniedException e = new AccessDeniedException("no");
        entryPoint.handle(request, response, e);
        verify(resolver).resolveException(org.mockito.ArgumentMatchers.same(request),
                org.mockito.ArgumentMatchers.same(response), isNull(), org.mockito.ArgumentMatchers.same(e));
    }
}
