package com.vn.vitalcare.config;

import com.vn.vitalcare.identity.auth.controller.AuthVersionAdvice;
import com.vn.vitalcare.share.web.ListResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS for the frontend dev server.
 *
 * <p>Both custom response headers have to be exposed explicitly: neither is one
 * of the CORS-safelisted headers, so without this the browser strips them. The
 * failure is silent in both cases and looks like a feature that was never
 * built — every list renders a total of 0, and a permission change never
 * reaches an open tab.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final String[] allowedOrigins;

    public WebMvcConfig(@Value("${app.cors.allowed-origins}") String[] allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders(ListResponse.TOTAL_COUNT_HEADER, AuthVersionAdvice.HEADER)
                .maxAge(3600);
    }
}
