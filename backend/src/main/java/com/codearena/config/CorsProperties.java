package com.codearena.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Origins permitted to call the API from a browser.
 *
 * <p>Configured per environment rather than hardcoded, and deliberately an explicit
 * allow-list: wildcard origins are never used because the API will carry credentials
 * once authentication lands.
 */
@ConfigurationProperties(prefix = "codearena.cors")
public record CorsProperties(List<String> allowedOrigins) {
}
