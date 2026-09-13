package com.codearena.executor.api;

import com.codearena.executor.sandbox.SandboxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Holds the workspaces that callers have open.
 *
 * <p>The HTTP contract is necessarily stateful — prepare, compile, run, run, run, close —
 * which means this process holds resources on behalf of somebody it cannot see. Two things
 * follow, and both are enforced here rather than trusted to the caller.
 *
 * <h2>A caller cannot open workspaces without limit</h2>
 * Each one costs a Docker volume and, while running, a container. {@link #capacity} bounds
 * how many may exist at once; past it, preparation is refused with 503 and the worker
 * reports a system error rather than a verdict. That is the bounded concurrency Phase 6
 * asks for: not a per-user quota, which is Phase 9's job and needs identity this service
 * deliberately does not have, but a hard ceiling on what the machine will be asked to run.
 *
 * <h2>A caller that disappears cannot leak</h2>
 * A worker killed between {@code prepare} and {@code close} would otherwise strand a volume
 * for ever. Workspaces idle longer than {@link #idleTimeout} are closed on a timer, and
 * every workspace is closed on shutdown. {@code SandboxReaper} is the layer below this one,
 * for when the process does not get to run its own cleanup at all.
 *
 * <p>Ids are random UUIDs generated here. A caller cannot guess another caller's workspace,
 * and cannot name a resource this service did not create for it.
 */
@Component
public class WorkspaceRegistry implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceRegistry.class);

    private final Map<String, Entry> workspaces = new ConcurrentHashMap<>();
    private final AtomicInteger open = new AtomicInteger();
    private final int capacity;
    private final Duration idleTimeout;
    private volatile boolean running;

    public WorkspaceRegistry(
            @Value("${codearena.sandbox.max-open-workspaces:16}") int capacity,
            @Value("${codearena.sandbox.workspace-idle-timeout:PT10M}") Duration idleTimeout) {
        this.capacity = capacity;
        this.idleTimeout = idleTimeout;
    }

    /** @throws CapacityExceededException when the service is already at its ceiling */
    public String register(SandboxService.Workspace workspace) {
        if (open.get() >= capacity) {
            // Closed immediately: the caller never learns of it, so nobody will close it.
            workspace.close();
            throw new CapacityExceededException(capacity);
        }
        open.incrementAndGet();
        String id = UUID.randomUUID().toString();
        workspaces.put(id, new Entry(workspace));
        return id;
    }

    /** @throws UnknownWorkspaceException when the id is wrong, expired, or already closed */
    public SandboxService.Workspace get(String id) {
        Entry entry = workspaces.get(id);
        if (entry == null) {
            throw new UnknownWorkspaceException();
        }
        entry.touch();
        return entry.workspace;
    }

    public void release(String id) {
        Entry entry = workspaces.remove(id);
        if (entry == null) {
            return;
        }
        open.decrementAndGet();
        entry.workspace.close();
    }

    public int openCount() {
        return open.get();
    }

    public int capacity() {
        return capacity;
    }

    /** Closes workspaces whose caller has gone away without saying so. */
    @Scheduled(fixedDelayString = "${codearena.sandbox.idle-sweep-ms:60000}")
    public void closeIdleWorkspaces() {
        Instant cutoff = Instant.now().minus(idleTimeout);
        for (Map.Entry<String, Entry> entry : workspaces.entrySet()) {
            if (entry.getValue().lastUsed.isBefore(cutoff)) {
                log.warn("event=WORKSPACE_ABANDONED workspace={} idleFor={}",
                        entry.getKey(), idleTimeout);
                release(entry.getKey());
            }
        }
    }

    @Override
    public void start() {
        running = true;
    }

    /** Nothing may outlive this process; a volume would survive it otherwise. */
    @Override
    public void stop() {
        running = false;
        int closed = 0;
        for (String id : Set.copyOf(workspaces.keySet())) {
            release(id);
            closed++;
        }
        if (closed > 0) {
            log.info("event=WORKSPACES_CLOSED_ON_SHUTDOWN count={}", closed);
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private static final class Entry {
        private final SandboxService.Workspace workspace;
        private volatile Instant lastUsed = Instant.now();

        private Entry(SandboxService.Workspace workspace) {
            this.workspace = workspace;
        }

        private void touch() {
            lastUsed = Instant.now();
        }
    }

    /** The service is already running as much as it is configured to run. */
    public static class CapacityExceededException extends RuntimeException {
        public CapacityExceededException(int capacity) {
            super("Execution capacity reached (" + capacity + " concurrent workspaces)");
        }
    }

    /** No such workspace. Deliberately indistinguishable from "not yours". */
    public static class UnknownWorkspaceException extends RuntimeException {
        public UnknownWorkspaceException() {
            super("No such workspace");
        }
    }
}
