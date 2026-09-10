package com.codearena.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI codeArenaOpenApi(@Value("${codearena.version}") String version) {
        return new OpenAPI().info(new Info()
                .title("CodeArena API")
                .version(version)
                .description("""
                        REST API for the CodeArena online judge.

                        Submissions are processed asynchronously: `POST /api/submissions` accepts a
                        job and returns immediately, and the judge worker reports progress out of band.
                        """)
                .license(new License().name("MIT")));
    }
}
