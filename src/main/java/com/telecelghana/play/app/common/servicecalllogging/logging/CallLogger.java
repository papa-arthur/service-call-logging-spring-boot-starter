package com.telecelghana.play.app.common.servicecalllogging.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Emits the single structured log entry per instrumented outbound call.
 *
 * <p>Constitution Principle VII (Data Hygiene) bounds this class absolutely: the only values
 * it may ever emit are the fields of {@link OutboundCallRecord} — source, destination, method,
 * HTTP status, status group, the parsed {@code responseCode}, the business message (admitted to
 * the fixed field set by constitution v1.1.0; logged verbatim, with no length bound), and the
 * destination URI path, inbound URI path and business operation (admitted by v1.2.0). It must
 * never reference a header bag, a request or response body, or any credential.
 * {@code DataHygieneArchTest} enforces this statically.

 * <p>The two URI fields are path components only, so a credential in a URI's userinfo or a token
 * in its query string is not representable here — the guarantee is structural, not a redaction
 * step (see {@code UriDataHygieneTest}).
 *
 * <p>Override by declaring your own {@code CallLogger} bean to change the log format.
 */
public class CallLogger {

    private static final Logger log = LoggerFactory.getLogger(CallLogger.class);

    private static final String ABSENT = "absent";
    private static final String NONE = "none";

    /**
     * Logs the request at INFO, immediately before it is dispatched (spec 003, FR-031).
     *
     * <p>This entry is why the single combined entry was split. Previously a call that hung until
     * the caller timed out left no trace of having been attempted; now the attempt is on record
     * with its full context regardless of whether a response ever arrives.
     *
     * <p>Emits only what is known at send time — no status, no response code, no message, because
     * none of them exist yet. A consumer subclassing {@code CallLogger} to change the format
     * <strong>must override this method too</strong>, or no send-time entry is emitted at all; see
     * the README's extension-point notes.
     *
     * @param record the call as known at send time; never null
     */
    public void logRequest(OutboundCallRecord record) {
        log.info("outbound-request source={} destination={} method={} destinationUri={} "
                        + "inboundUri={} operation={}",
                record.source(),
                record.destination(),
                record.httpMethod(),
                record.destinationUri(),
                record.inboundUri(),
                record.operation());
    }

    /**
     * Logs the response at INFO, once it has been received or the call has failed.
     *
     * <p>Identified by {@code outbound-req-response}, replacing the {@code outbound-call} naming
     * this entry used before spec 003. Note that {@link #logWarn} deliberately keeps its
     * {@code outbound-call-instrumentation-error} text — it is not per-call telemetry, so a
     * prefix match on {@code outbound-call} now catches only that warning.
     *
     * @param record the completed call; never null
     */
    public void log(OutboundCallRecord record) {
        log.info("outbound-req-response source={} destination={} method={} destinationUri={} inboundUri={} "
                        + "operation={} httpStatus={} httpStatusGroup={} "
                        + "responseCode={} responseMessage={}",
                record.source(),
                record.destination(),
                record.httpMethod(),
                record.destinationUri(),
                record.inboundUri(),
                record.operation(),
                record.httpStatusCode() == null ? NONE : record.httpStatusCode(),
                record.httpStatusGroup(),
                record.responseCode() == null ? ABSENT : record.responseCode(),
                record.message() == null ? ABSENT : record.message());
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
