package com.codearena.worker;

import com.codearena.worker.config.WorkerProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Entry point for the CodeArena judge worker.
 *
 * <p>The worker is the only component that ever touches user-submitted code, and it
 * never runs that code in this process: each submission is compiled and executed inside
 * a disposable, network-isolated Docker container with CPU, memory and wall-clock
 * limits applied by the container runtime.
 *
 * <p>Workers are horizontally scalable. Several instances consume from the same Redis
 * queue concurrently, so nothing here may assume it is the only worker running.
 */
@SpringBootApplication
@EnableConfigurationProperties(WorkerProperties.class)
public class WorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorkerApplication.class, args);
    }
}
