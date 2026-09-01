package com.bookit.servicecalllogging.metrics;

/**
 * Immutable carrier for an extracted {@code responseCode} plus its interpretation.
 *
 * <p>{@code rawCode} is meaningful only when {@code outcome != } {@link Outcome#ABSENT};
 * callers must check {@link #outcome()} before reading it.
 *
 * @param outcome the interpreted result; never null
 * @param rawCode the raw integer read from the response envelope, or null when absent
 */
public record ResponseCodeResult(Outcome outcome, Integer rawCode) {

    /** Shared instance for "no {@code responseCode} could be determined". */
    public static final ResponseCodeResult ABSENT = new ResponseCodeResult(Outcome.ABSENT, null);

    /**
     * Builds a result from a successfully extracted code.
     *
     * @param rawCode the extracted value; {@code 0} maps to {@link Outcome#SUCCESS}, every
     *                other value maps to {@link Outcome#FAILURE}
     */
    public static ResponseCodeResult of(int rawCode) {
        return new ResponseCodeResult(rawCode == 0 ? Outcome.SUCCESS : Outcome.FAILURE, rawCode);
    }
}
