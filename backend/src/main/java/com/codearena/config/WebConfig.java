package com.codearena.config;

import com.codearena.ratelimit.RateLimitInterceptor;
import com.codearena.ratelimit.RateLimitProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Web and persistence infrastructure that is not security policy itself.
 *
 * <p>JPA auditing populates {@code createdAt} and {@code updatedAt} on entities that ask
 * for it, so no service has to remember to set them.
 */
@Configuration
@EnableConfigurationProperties({CorsProperties.class, RateLimitProperties.class})
@EnableJpaAuditing
public class WebConfig implements WebMvcConfigurer {

    private final CorsProperties corsProperties;
    private final RateLimitInterceptor rateLimitInterceptor;

    public WebConfig(CorsProperties corsProperties, RateLimitInterceptor rateLimitInterceptor) {
        this.corsProperties = corsProperties;
        this.rateLimitInterceptor = rateLimitInterceptor;
    }

    /**
     * Rate limiting is registered across the whole API and decides per handler.
     *
     * <p>A broad path pattern with per-handler opt-in, rather than a list of protected
     * paths. The interceptor does nothing at all to a handler without a
     * {@link com.codearena.ratelimit.RateLimited} annotation, so the mapping costs a single
     * annotation lookup on unprotected routes -- and, crucially, a protected handler cannot
     * be reached by a spelling of its path that somebody forgot to add here.
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor).addPathPatterns("/**");
    }

    /**
     * CORS is published as a bean rather than through {@code WebMvcConfigurer} so that the
     * Spring Security filter chain applies it.
     *
     * <p>This matters: security filters run before Spring MVC, so an MVC-only CORS
     * mapping would let security reject a browser preflight before the CORS handler ever
     * saw it. Defining it once, here, keeps a single source of truth and puts it in the
     * layer that actually needs to honour it.
     *
     * <p>Origins are an explicit allow-list and credentials are permitted, because the
     * session cookie has to travel with API calls. A wildcard origin is not merely
     * discouraged with credentials enabled, it is rejected by the browser.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(corsProperties.allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Content-Type", "Accept", "X-XSRF-TOKEN", "X-Requested-With"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}
