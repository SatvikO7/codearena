package com.codearena.ratelimit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Applies a rate-limit policy to one handler method.
 *
 * <p>Marking the handler rather than configuring a path pattern is a security decision, not
 * a stylistic one. A pattern-matched rule has to be written twice — once as a route and once
 * as a limiter — and the two drift. Every classic bypass is that drift:
 *
 * <ul>
 *   <li>a trailing slash, {@code /api/submissions/} against a rule written for
 *       {@code /api/submissions}</li>
 *   <li>a path the router normalises differently from the matcher — {@code %2e%2e},
 *       duplicate slashes, a case difference</li>
 *   <li>a second route that reaches the same handler, such as submitting through the
 *       contest endpoint instead of the practice one</li>
 *   <li>a method change that falls through to a rule written for {@code POST}</li>
 * </ul>
 *
 * <p>None of these exist here. Spring has already resolved the request to a handler by the
 * time the limit is applied, so whatever spelling, method or route got the caller to that
 * method, the same allowance is spent. A handler with no annotation is unlimited by an
 * explicit decision that is visible in the code review, rather than by a rule somebody
 * forgot to add to a list somewhere else.
 *
 * @see RateLimitInterceptor
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimited {

    /** The policy whose allowance this handler spends. */
    RateLimitPolicy value();

    /**
     * Limit only when this request parameter is present and non-blank.
     *
     * <p>Empty means "always limit". It exists for the one case where the same handler does
     * two jobs of very different cost: the problem catalogue is an indexed page when it is
     * listing and a trigram search when a term is supplied, and only the second is worth
     * limiting.
     */
    String onlyWhenParameterPresent() default "";
}
