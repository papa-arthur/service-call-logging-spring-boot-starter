package com.bookit.servicecalllogging.operation;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves the business operation a call represents, from the fixed {@code X-Operation} request
 * header the calling code supplies.
 *
 * <p>This is what turns the telemetry from infrastructure monitoring into business-outcome
 * monitoring: it lets an operator ask "how much of our SendMoney traffic is succeeding?" rather
 * than only "how much traffic went to the payments service?".
 *
 * <h2>Two bounds, protecting two different things</h2>
 *
 * <p>The <strong>shape</strong> bound (FR-020) constrains a single value: at most
 * {@value #MAX_LENGTH} characters from {@code [A-Za-z0-9._-]}. It excludes whitespace and
 * separators, which would break log-line parsing, and makes a value carrying an account identifier
 * or free text unlikely to pass by accident.
 *
 * <p>The <strong>distinct-value cap</strong> (FR-021) constrains how many different values exist.
 * The shape bound cannot do this: {@code SendMoney-0001}, {@code SendMoney-0002} … all satisfy it
 * while being unbounded in number, and each new value is a new metric series. At most
 * {@value #MAX_DISTINCT_VALUES} distinct values are admitted per process; every further
 * previously-unseen value reports as {@value #UNDEFINED}. Without this second bound, SC-009's
 * bounded-cardinality guarantee would simply be false.
 *
 * <p><strong>First-come-first-served, with no eviction and no ranking by volume.</strong> FR-021
 * forbids ranking deliberately: a dimension whose membership shifted under load would change a
 * dashboard's meaning in the middle of an incident, which is worse than a stable, documented cap.
 * A high-volume junk caller therefore cannot displace an operation already admitted.
 *
 * <p>Neither bound is configurable. The dimension they protect is shared infrastructure — a
 * consuming service raising its own cap would spend cardinality everyone else pays for.
 *
 * <p>The header is only ever <em>read</em>. Nothing here writes, removes or rewrites it, so a value
 * rejected for telemetry still reaches the destination exactly as the calling code set it (FR-024).
 *
 * <p>Replaceable: declare your own {@code OperationResolver} bean to change this behaviour.
 */
public class OperationResolver {

    /**
     * The one header this dimension is read from. Fixed, never configurable (FR-018): it is a
     * per-call input supplied by the caller, not a per-service identity like the correlation
     * headers, so there is nothing for a service-wide property to say about it.
     */
    public static final String HEADER_NAME = "X-Operation";

    /** Reported whenever no usable operation was supplied. */
    public static final String UNDEFINED = "undefined";

    /** Generous enough for any descriptive name — {@code SendMoney}, {@code ReverseTransfer}. */
    public static final int MAX_LENGTH = 64;

    /**
     * Comfortably above the number of named business operations one service plausibly performs, so
     * a well-behaved consumer never reaches it, yet low enough that a misbehaving caller cannot
     * damage a metric store.
     */
    public static final int MAX_DISTINCT_VALUES = 100;

    /**
     * The values admitted so far in this process. Bounded at
     * {@value #MAX_DISTINCT_VALUES} × {@value #MAX_LENGTH} characters — a few KB, well inside
     * Principle VI's bounded-cost rule.
     *
     * <p>A benign race on the size check may admit a small number beyond the cap under heavy
     * concurrent first-sightings. That is accepted: the requirement is boundedness, not an exact
     * ceiling, and paying for a lock on every call to make the ceiling exact would cost the
     * business call for no operational benefit.
     */
    private final Set<String> admitted = ConcurrentHashMap.newKeySet();

    /**
     * @param headerValue the raw {@code X-Operation} value, or null when the caller sent none
     * @return the operation to record — never null, never blank
     */
    public String resolve(String headerValue) {
        if (!hasAcceptableShape(headerValue)) {
            return UNDEFINED;
        }
        if (this.admitted.contains(headerValue)) {
            return headerValue;
        }
        if (this.admitted.size() >= MAX_DISTINCT_VALUES) {
            return UNDEFINED;
        }
        this.admitted.add(headerValue);
        return headerValue;
    }

    /**
     * A hand-rolled character scan rather than a regular expression, deliberately: this runs on
     * caller-controlled input on every outbound call, and a regex would put a backtracking engine
     * in that path for no benefit. A linear scan is faster and obviously bounded.
     */
    private static boolean hasAcceptableShape(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_LENGTH) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!isPermitted(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isPermitted(char c) {
        return (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || c == '.' || c == '_' || c == '-';
    }
}
