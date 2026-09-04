package com.telecelghana.play.app.common.servicecalllogging.metrics;

/**
 * The interpreted result of an outbound call's response envelope {@code responseCode}.
 *
 * <p>{@link #label()} is the value published as the {@code outcome} metric tag; the three
 * labels here are the complete, documented set (see {@code contracts/metrics-schema.md}).
 */
public enum Outcome {

    /** {@code responseCode} was extracted and equals {@code 0}. */
    SUCCESS("success"),

    /** {@code responseCode} was extracted and is non-zero. */
    FAILURE("failure"),

    /**
     * {@code responseCode} could not be extracted: the body was missing, empty, non-JSON,
     * lacked the field, exceeded the configured cap, or the extractor failed.
     */
    ABSENT("absent");

    private final String label;

    Outcome(String label) {
        this.label = label;
    }

    /**
     * @return the stable metric tag value for this outcome; never null
     */
    public String label() {
        return this.label;
    }
}
