package com.codearena.executor.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Removes sandbox containers and volumes that nothing owns any more.
 *
 * <p>{@link DockerSandboxService} removes its own resources in a {@code finally}, so in
 * normal operation this finds nothing. It exists for the cases a {@code finally} cannot
 * cover: the process is killed between starting a container and removing it, the daemon is
 * unreachable at exactly the wrong moment, or a cleanup call times out. Without it those
 * leak containers and volumes until a human notices.
 *
 * <h2>Why it cannot eat a live execution</h2>
 * This is the dangerous part of a reaper and it is handled explicitly, because deleting a
 * concurrent submission's workspace would corrupt a verdict rather than merely waste space:
 *
 * <ul>
 *   <li><b>Volumes</b> are only considered when Docker reports them as <em>dangling</em> —
 *       attached to no container at all — <em>and</em> older than {@link #graceMinutes}.
 *       The grace period covers the one window where a live workspace is legitimately
 *       dangling: between {@code volume create} and the first container that mounts it.</li>
 *   <li><b>Containers</b> are only considered when they have stopped. A running container
 *       is always left alone, because it is either someone's execution in flight or it will
 *       stop on its own and be collected next time.</li>
 *   <li><b>At startup</b> the rules are relaxed to include running containers, and only
 *       then. Nothing of ours can legitimately be running before this process has started
 *       anything, so anything wearing our label is by definition from a previous life.</li>
 * </ul>
 *
 * <p>Everything is matched by the {@link SandboxPolicy#OWNER_LABEL} label, never by name
 * prefix, so the reaper cannot touch a container that merely looks like ours.
 */
@Component
public class SandboxReaper {

    private static final Logger log = LoggerFactory.getLogger(SandboxReaper.class);

    private static final String LABEL_FILTER = "label=" + SandboxPolicy.OWNER_LABEL + "=true";

    private final SandboxMetrics metrics;
    private final String dockerBinary;
    private final long graceMinutes;

    public SandboxReaper(SandboxMetrics metrics,
                         @Value("${codearena.sandbox.docker-binary:docker}") String dockerBinary,
                         @Value("${codearena.sandbox.reaper-grace-minutes:15}") long graceMinutes) {
        this.metrics = metrics;
        this.dockerBinary = dockerBinary;
        this.graceMinutes = graceMinutes;
    }

    /**
     * Clears anything left behind by a previous run of this process.
     *
     * <p>Deliberately not fatal. A daemon that will not answer at startup is a problem the
     * health check reports; refusing to boot over it would turn a recoverable condition into
     * an outage.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void sweepOnStartup() {
        try {
            int removed = reapContainers(true) + reapVolumes(true);
            if (removed > 0) {
                log.warn("event=SANDBOX_STARTUP_SWEEP removed={} note=left_by_a_previous_run", removed);
            }
        } catch (RuntimeException e) {
            log.warn("event=SANDBOX_STARTUP_SWEEP_FAILED reason={}", e.toString());
        }
    }

    @Scheduled(fixedDelayString = "${codearena.sandbox.reaper-interval-ms:300000}",
               initialDelayString = "${codearena.sandbox.reaper-interval-ms:300000}")
    public void sweep() {
        try {
            int removed = reapContainers(false) + reapVolumes(false);
            if (removed > 0) {
                metrics.reaped(removed);
            }
        } catch (RuntimeException e) {
            log.warn("event=SANDBOX_SWEEP_FAILED reason={}", e.toString());
        }
    }

    private int reapContainers(boolean includeRunning) {
        List<String> command = new ArrayList<>(List.of(
                dockerBinary, "ps", "--all", "--quiet", "--filter", LABEL_FILTER));
        if (!includeRunning) {
            // Stopped only. A running container is someone's execution until proven otherwise.
            command.addAll(List.of("--filter", "status=exited",
                                   "--filter", "status=created",
                                   "--filter", "status=dead"));
        }

        int removed = 0;
        for (String id : lines(run(command))) {
            if (removeQuietly(List.of(dockerBinary, "rm", "--force", "--volumes", id))) {
                removed++;
            }
        }
        return removed;
    }

    private int reapVolumes(boolean ignoreGrace) {
        List<String> names = lines(run(List.of(
                dockerBinary, "volume", "ls", "--quiet",
                "--filter", "dangling=true",
                "--filter", LABEL_FILTER)));

        int removed = 0;
        for (String name : names) {
            if (!ignoreGrace && !olderThanGrace(name)) {
                // Almost certainly a workspace that has been created but not yet mounted.
                continue;
            }
            if (removeQuietly(List.of(dockerBinary, "volume", "rm", "--force", name))) {
                removed++;
            }
        }
        return removed;
    }

    /**
     * @return true when the volume is old enough to be certain nobody is about to mount it.
     *         An unreadable or unparseable timestamp answers false, because leaving a volume
     *         alone costs disk and removing a live one costs a verdict.
     */
    private boolean olderThanGrace(String volume) {
        String created = run(List.of(dockerBinary, "volume", "inspect",
                "--format", "{{.CreatedAt}}", volume)).strip();
        if (created.isBlank()) {
            return false;
        }
        try {
            Instant createdAt = OffsetDateTime.parse(created, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
            return createdAt.isBefore(Instant.now().minus(Duration.ofMinutes(graceMinutes)));
        } catch (RuntimeException e) {
            log.debug("unparseable volume timestamp {} for {}", created, volume);
            return false;
        }
    }

    private boolean removeQuietly(List<String> command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
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

    private String run(List<String> command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = read(process.getInputStream());
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "";
            }
            return process.exitValue() == 0 ? output : "";
        } catch (IOException e) {
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
    }

    private static List<String> lines(String output) {
        return output.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
    }

    private static String read(InputStream stream) throws IOException {
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
