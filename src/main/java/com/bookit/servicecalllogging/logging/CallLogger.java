package com.bookit.servicecalllogging.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Emits the single structured log entry per instrumented outbound call.
 *
 * <p>Constitution Principle VII (Data Hygiene) bounds this class absolutely: the only values
 * it may ever emit are the fields of {@link OutboundCallRecord} — source, destination, method,
 * HTTP status, status group, and the parsed {@code responseCode}. It must never reference a
 * header bag, a request or response body, or any credential. {@code DataHygieneArchTest}
 * enforces this statically.
 *
 * <p>Override by declaring your own {@code CallLogger} bean to change the log format.
 */
public class CallLogger {

    private static final Logger log = LoggerFactory.getLogger(CallLogger.class);

    private static final String ABSENT = "absent";
    private static final String NONE = "none";

    /**
     * Logs one completed call at INFO. Callers must never let a failure here reach the
     * business call; the instrumentation path wraps this invocation.
     *
     * @param record the call to log; never null
     */
    public void log(OutboundCallRecord record) {
        log.info("outbound-call source={} destination={} method={} httpStatus={} httpStatusGroup={} responseCode={}",
                record.source(),
                record.destination(),
                record.httpMethod(),
                record.httpStatusCode() == null ? NONE : record.httpStatusCode(),
                record.httpStatusGroup(),
                record.responseCode() == null ? ABSENT : record.responseCode());
    }

    /**
     * Logs a failure that occurred inside the instrumentation path itself at WARN. The
     * business call is unaffected and has already been (or is about to be) returned intact.
     *
     * <p>Only the exception type and message are emitted — never a stack trace containing
     * request state, and never the throwable's cause chain.
     *
     * @param source      the calling service name
     * @param destination the destination service name
     * @param error       the instrumentation failure; never null
     */
    public void logWarn(String source, String destination, Throwable error) {
        log.warn("outbound-call-instrumentation-error source={} destination={} errorType={} errorMessage={}",
                source,
                destination,
                error.getClass().getName(),
                error.getMessage());
    }
}
