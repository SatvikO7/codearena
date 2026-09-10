package com.codearena.system;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * Exposes non-sensitive build and runtime metadata.
 *
 * <p>The frontend calls this on startup to confirm it can reach the API. Nothing
 * returned here is secret: no configuration values, credentials or connection
 * strings are included.
 */
@RestController
@RequestMapping("/api/system")
@Tag(name = "System", description = "Service metadata and connectivity checks")
public class SystemInfoController {

    private final String applicationName;
    private final String applicationVersion;
    private final Environment environment;

    public SystemInfoController(
            @Value("${spring.application.name}") String applicationName,
            @Value("${codearena.version}") String applicationVersion,
            Environment environment) {
        this.applicationName = applicationName;
        this.applicationVersion = applicationVersion;
        this.environment = environment;
    }

    @GetMapping("/info")
    @Operation(summary = "Service metadata",
               description = "Returns the service name, version, active profiles and server time.")
    public SystemInfo info() {
        return new SystemInfo(
                applicationName,
                applicationVersion,
                List.of(environment.getActiveProfiles()),
                Instant.now());
    }

    public record SystemInfo(String service, String version, List<String> profiles, Instant serverTime) {
    }
}
