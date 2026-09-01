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
     * <p>Only the single successful value is privileged: every other value the code field can
     * hold — including one the starter has never seen before — is a valid unsuccessful outcome,
     * never "absent" or "unrecognised" (FR-009).
     *
     * @param rawCode         the extracted value, logged as-is whatever it is
     * @param successfulValue the applicable combination's successful value; {@code rawCode}
     *                        equal to it maps to {@link Outcome#SUCCESS}, everything else to
     *                        {@link Outcome#FAILURE}
     */
    public static ResponseCodeResult of(int rawCode, int successfulValue) {
        return new ResponseCodeResult(rawCode == successfulValue ? Outcome.SUCCESS : Outcome.FAILURE, rawCode);
    }
}
