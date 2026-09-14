package com.codearena.system;

import com.codearena.shared.Language;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Domain events and operational state, as metrics.
 *
 * <h2>Business metrics, kept apart from HTTP metrics</h2>
 * Actuator already records {@code http.server.requests} — rate, latency and status per
 * route — and that answers "is the API healthy". It cannot answer "are submissions being
 * judged", because a submission that is accepted, queued and never judged is a stream of
 * successful 202s. The counters here describe what the system <em>did</em>, not what it
 * replied, and the two go wrong independently.
 *
 * <h2>The rule about labels</h2>
 * Every tag is a closed set: a language, a verdict, an outcome, a kind. No user, no
 * submission, no problem, no contest, no address, no username appears in a label anywhere
 * in this system. That is not tidiness — a metric tagged with an identifier creates a time
 * series per value, and a monitoring backend that gets one per submission falls over
 * exactly when the traffic that broke it is the thing you need to look at. Identifiers live
 * in logs, one line each, searchable by the submission id that runs through the whole
 * pipeline.
 *
 * <h2>Counters are recorded where the thing happens</h2>
 * Not in a filter that guesses from a status code. A 201 from the registration endpoint is
 * an account; a 409 is not; a 429 is not. Only the code that created the account knows
 * which, so that is where it is counted.
 */
@Component
public class BusinessMetrics {

    private final MeterRegistry registry;

    public BusinessMetrics(MeterRegistry registry, WorkerDirectory workers, QueueHealth queueHealth) {
        this.registry = registry;

        // Two series for workers, tagged by state -- never one per worker. Workers come
        // and go, which is the point of them, so a gauge keyed on a worker id would
        // leave a series behind for every container that ever ran, each reporting its
        // last value forever. Healthy and stale counts say everything a dashboard needs.
        //
        // Both read through WorkerDirectory's cache, and both are suppliers rather than
        // values pushed from somewhere: a gauge that only updates when an administrator
        // happens to open a page would report nothing at all in a deployment nobody is
        // watching, which is exactly the deployment worth monitoring.
        Gauge.builder("codearena.workers", () -> countWorkers(workers, true))
                .tag("state", "healthy")
                .description("Judge workers that are still reporting")
                .register(registry);
        Gauge.builder("codearena.workers", () -> countWorkers(workers, false))
                .tag("state", "stale")
                .description("Judge workers whose record exists but has gone quiet")
                .register(registry);
        // Queue depth is a gauge over a supplier rather than a counter: it is a level, not
        // an event, and the supplier is only called when the endpoint is scraped.
        Gauge.builder("codearena.queue.depth", () -> orZero(queueHealth.measure().pending()))
                .tag("state", "pending")
                .description("Submissions waiting for a worker")
                .register(registry);
        Gauge.builder("codearena.queue.depth", () -> orZero(queueHealth.measure().processing()))
                .tag("state", "processing")
                .description("Submissions a worker has taken but not finished")
                .register(registry);
        Gauge.builder("codearena.queue.oldest.age.seconds",
                        () -> orZero(queueHealth.measure().oldestPendingAgeSeconds()))
                .description("How long the longest-waiting submission has been waiting")
                .register(registry);

        preRegisterCounters();
    }

    /**
     * Creates every counter at startup, at zero.
     *
     * <p>Micrometer registers a counter the first time it is incremented, so without this
     * a freshly started system exposes no submission counter at all -- and a dashboard
     * shows a gap rather than a zero. Worse, an alert cannot tell "nothing has been
     * submitted" from "the metric is missing", which are very different things to be
     * looking at during an incident.
     *
     * <p>Only possible because every label here is a closed set. It is the same property
     * that keeps cardinality bounded, used a second time: a fixed set of series can be
     * created in advance precisely because it is fixed.
     */
    private void preRegisterCounters() {
        for (Language language : Language.values()) {
            for (String kind : new String[]{"practice", "contest"}) {
                registry.counter("codearena.submissions.accepted",
                        "language", language.name(), "kind", kind);
            }
        }
        for (String operation : new String[]{"login", "register"}) {
            for (String outcome : new String[]{"success", "failure"}) {
                registry.counter("codearena.auth.attempts",
                        "operation", operation, "outcome", outcome);
            }
        }
        for (String outcome : new String[]{"success", "failure"}) {
            registry.counter("codearena.audit.writes", "outcome", outcome);
        }
        registry.counter("codearena.contests.registrations");
    }

    // ------------------------------------------------------------------ authentication

    /** @param operation {@code login} or {@code register}; @param outcome success or failure */
    public void authAttempt(String operation, String outcome) {
        registry.counter("codearena.auth.attempts", "operation", operation, "outcome", outcome)
                .increment();
    }

    // ------------------------------------------------------------------ submissions

    /**
     * A submission was accepted for judging.
     *
     * <p>Counted here, at acceptance; the worker counts verdicts separately. The gap between
     * the two is the single most diagnostic number in the system — if accepted keeps rising
     * and judged does not, the judge has stopped, and no amount of HTTP metrics would have
     * said so.
     *
     * @param kind {@code practice} or {@code contest}
     */
    public void submissionAccepted(Language language, String kind) {
        registry.counter("codearena.submissions.accepted",
                "language", language.name(), "kind", kind).increment();
    }

    // ------------------------------------------------------------------ contests

    public void contestRegistration() {
        registry.counter("codearena.contests.registrations").increment();
    }

    // ------------------------------------------------------------------ audit

    /**
     * An audit write, and whether it succeeded.
     *
     * <p>Failures matter more than successes here. An audit write that fails on a
     * security-critical change is meant to fail the change with it (ADR-037); one that fails
     * on a login failure is swallowed by design. Either way a non-zero rate means the system
     * is recording less than it claims to, which is a thing to know before an investigation
     * needs the records rather than during one.
     */
    public void auditWrite(String outcome) {
        registry.counter("codearena.audit.writes", "outcome", outcome).increment();
    }

    // ------------------------------------------------------------------ workers

    private static double countWorkers(WorkerDirectory workers, boolean healthy) {
        return workers.workers().stream()
                .filter(worker -> worker.healthy() == healthy)
                .count();
    }

    private static double orZero(Long value) {
        return value == null ? 0d : value;
    }
}
