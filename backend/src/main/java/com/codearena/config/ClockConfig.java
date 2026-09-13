package com.codearena.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * The application's clock, as a bean.
 *
 * <h2>Why a bean rather than {@code Instant.now()}</h2>
 * Contests are the first feature whose correctness is a function of the current time:
 * whether a contest is live, whether registration is open, whether a submission arrived four
 * seconds before the deadline or four seconds after. Those rules need testing at the
 * boundary — at exactly {@code startAt}, at exactly {@code endAt}, one millisecond either
 * side — and a test that has to wait for a real clock to reach a boundary cannot test the
 * boundary at all. It can only test somewhere nearby, slowly, and flakily.
 *
 * <p>Injecting the clock means those tests substitute a fixed one and assert the rule
 * directly. Nothing else changes: in production this is the system clock.
 *
 * <h2>UTC, explicitly</h2>
 * {@link Clock#systemUTC()} rather than {@code systemDefaultZone()}. Every contest timestamp
 * is stored and compared in UTC, and a server whose local zone is IST must not produce
 * different contest behaviour from one in UTC. The zone belongs to the browser, which
 * renders the instant however the reader wants to see it.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
