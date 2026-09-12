package com.codearena;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the CodeArena API server.
 *
 * <p>This process serves the public REST API and enqueues submission jobs. It never
 * executes user-submitted code &mdash; that is the exclusive responsibility of the
 * worker process, which runs each submission inside a disposable Docker container.
 */
@SpringBootApplication
// Drives the submission recovery sweeper, which republishes lost queue jobs and
// reclaims submissions from workers that died holding a claim.
@EnableScheduling
public class CodeArenaApplication {

    public static void main(String[] args) {
        SpringApplication.run(CodeArenaApplication.class, args);
    }
}
