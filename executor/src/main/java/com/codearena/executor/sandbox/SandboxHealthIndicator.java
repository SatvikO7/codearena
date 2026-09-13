package com.codearena.executor.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Reports whether this service can actually execute anything.
 *
 * <p>Two conditions, both of which have silently produced a run of failed submissions
 * before being made visible here:
 *
 * <ol>
 *   <li><b>The daemon answers.</b> A socket that is missing, unreadable or wedged means
 *       every submission becomes a SYSTEM_ERROR. That should show as an unhealthy service,
 *       not as a stream of confusing verdicts.</li>
 *   <li><b>Every sandbox image is present.</b> Sandbox containers are created with
 *       {@code --pull never}, deliberately — judging must never reach a registry — so a
 *       missing image is a hard failure rather than a slow first run. Somebody who forgets
 *       {@code sandbox/build-images.sh} after a fresh clone finds out here.</li>
 * </ol>
 *
 * <p>The detail is deliberately thin: which images are missing, and nothing about the
 * daemon, the host or the configuration. Health output is the most widely readable thing a
 * service produces and it is not a place to describe infrastructure.
 */
@Component
public class SandboxHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(SandboxHealthIndicator.class);

    private final String dockerBinary;
    private final SandboxPolicy policy;

    public SandboxHealthIndicator(SandboxPolicy policy,
                                  @Value("${codearena.sandbox.docker-binary:docker}") String dockerBinary) {
        this.policy = policy;
        this.dockerBinary = dockerBinary;
    }

    @Override
    public Health health() {
        if (!daemonResponds()) {
            return Health.down().withDetail("runtime", "unreachable").build();
        }

        List<String> missing = new ArrayList<>();
        for (String image : LanguageSpec.allImages()) {
            if (!imagePresent(image)) {
                missing.add(image);
            }
        }
        if (!missing.isEmpty()) {
            log.error("event=SANDBOX_IMAGES_MISSING images={} fix=run_sandbox_build-images.sh", missing);
            return Health.down()
                    .withDetail("missingImages", missing)
                    .withDetail("fix", "run sandbox/build-images.sh")
                    .build();
        }

        return Health.up()
                .withDetail("seccompProfileApplied", policy.seccompProfileApplied())
                .build();
    }

    private boolean daemonResponds() {
        // `version --format` rather than `info`: it is the cheapest call that proves the
        // client reached the daemon, and it returns nothing worth leaking.
        return succeeds(List.of(dockerBinary, "version", "--format", "{{.Server.APIVersion}}"));
    }

    private boolean imagePresent(String image) {
        return succeeds(List.of(dockerBinary, "image", "inspect", image));
    }

    private boolean succeeds(List<String> command) {
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
