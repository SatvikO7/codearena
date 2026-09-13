package com.codearena.executor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The execution service: the only CodeArena process that can create containers.
 *
 * <p>It exists so that nothing else has to. Before Phase 6 the judge worker held the Docker
 * socket, which meant the process that parses untrusted program output, talks to PostgreSQL
 * and Redis, and runs the judging logic was also the process that could start a privileged
 * container mounting the host filesystem. Splitting them means a compromise of the worker
 * yields the narrow contract in {@code ExecutionApi} — "compile and run this source in one
 * of three languages, within these limits" — instead of host-equivalent control.
 *
 * <p>This process is kept deliberately dull: no database, no queue, no user model, one
 * caller, one shared secret, four endpoints. The less it does, the less there is to take.
 *
 * <p>The risk that remains is stated rather than solved: whatever holds the socket is
 * host-equivalent if <em>it</em> is compromised, and that is now this. See ADR-028 and
 * docs/threat-model.md.
 */
@SpringBootApplication
@EnableScheduling
public class ExecutorApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExecutorApplication.class, args);
    }
}
