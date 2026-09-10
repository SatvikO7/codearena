package com.codearena.worker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunables for a single worker process.
 *
 * @param id          stable identity of this worker, used in logs and (from Phase 5)
 *                    as the owner of a job lease. Defaults to the container hostname,
 *                    which docker compose makes unique per replica.
 * @param concurrency how many submissions this process judges in parallel. Each one
 *                    occupies a Docker container, so this bounds the container count
 *                    contributed by this worker.
 */
@ConfigurationProperties(prefix = "codearena.worker")
public record WorkerProperties(String id, int concurrency) {

    public WorkerProperties {
        if (concurrency < 1) {
            throw new IllegalArgumentException("codearena.worker.concurrency must be at least 1");
        }
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("codearena.worker.id must not be blank");
        }
    }
}
