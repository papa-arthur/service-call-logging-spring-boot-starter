package com.bookit.servicecalllogging.testsupport;

import com.bookit.servicecalllogging.ServiceCallLoggingProperties;

import java.util.List;

/**
 * Factory for {@link ServiceCallLoggingProperties} instances in unit tests, so a change to the
 * property set does not ripple through every test constructor call.
 */
public final class TestProperties {

    private TestProperties() {
    }

    public static ServiceCallLoggingProperties.Metrics defaultMetrics() {
        return new ServiceCallLoggingProperties.Metrics(
                "http.outbound.calls", "destination", "outcome", "http_status_group");
    }

    public static ServiceCallLoggingProperties defaults() {
        return withMaxBodyBytes(1_048_576);
    }

    public static ServiceCallLoggingProperties withMaxBodyBytes(int maxBodyBytes) {
        return new ServiceCallLoggingProperties(
                true, "X-Source-Service", "X-Destination-Service", "service_name",
                maxBodyBytes, defaultMetrics(), List.of());
    }

    public static ServiceCallLoggingProperties withEnvelopes(ServiceCallLoggingProperties.Envelope... envelopes) {
        return new ServiceCallLoggingProperties(
                true, "X-Source-Service", "X-Destination-Service", "service_name",
                1_048_576, defaultMetrics(), List.of(envelopes));
    }

    public static ServiceCallLoggingProperties withHeaderNames(String sourceHeader, String destinationHeader) {
        return new ServiceCallLoggingProperties(
                true, sourceHeader, destinationHeader, "service_name", 1_048_576, defaultMetrics(), List.of());
    }
}
