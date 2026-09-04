package com.telecelghana.play.app.common.servicecalllogging.testsupport;

import com.telecelghana.play.app.common.servicecalllogging.logging.CallLogger;
import com.telecelghana.play.app.common.servicecalllogging.logging.OutboundCallRecord;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A {@link CallLogger} that captures records instead of emitting them, so tests can assert on
 * the exact telemetry the instrumentation produced rather than on log text.
 */
public class RecordingCallLogger extends CallLogger {

    private final List<OutboundCallRecord> records = new CopyOnWriteArrayList<>();
    private final List<Throwable> warnings = new CopyOnWriteArrayList<>();

    @Override
    public void log(OutboundCallRecord record) {
        this.records.add(record);
    }

    @Override
    public void logWarn(String source, String destination, Throwable error) {
        this.warnings.add(error);
    }

    public List<OutboundCallRecord> records() {
        return this.records;
    }

    public List<Throwable> warnings() {
        return this.warnings;
    }

    public OutboundCallRecord onlyRecord() {
        if (this.records.size() != 1) {
            throw new AssertionError("expected exactly one call record but captured " + this.records.size()
                    + ": " + this.records);
        }
        return this.records.get(0);
    }

    public void reset() {
        this.records.clear();
        this.warnings.clear();
    }
}
