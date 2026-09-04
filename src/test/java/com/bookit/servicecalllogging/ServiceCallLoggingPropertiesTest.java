package com.bookit.servicecalllogging;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceCallLoggingPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of())
            .withUserConfiguration(PropertiesEnabledConfig.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ServiceCallLoggingProperties.class)
    static class PropertiesEnabledConfig {
    }

    @Test
    void allBaselineKeysBindToTheirDocumentedDefaults() {
        runner.run(context -> {
            ServiceCallLoggingProperties properties = context.getBean(ServiceCallLoggingProperties.class);

            assertThat(properties.enabled()).isTrue();
            assertThat(properties.sourceHeaderName()).isEqualTo("X-Source-Service");
            assertThat(properties.destinationHeaderName()).isEqualTo("X-Destination-Service");
            assertThat(properties.serviceNameHintHeader()).isEqualTo("service_name");
            assertThat(properties.maxBodyBytes()).isEqualTo(1_048_576);

            assertThat(properties.metrics()).isNotNull();
            assertThat(properties.metrics().prefix()).isEqualTo("http.outbound.calls");
            assertThat(properties.metrics().destinationTagName()).isEqualTo("destination");
            assertThat(properties.metrics().outcomeTagName()).isEqualTo("outcome");
            assertThat(properties.metrics().statusGroupTagName()).isEqualTo("http_status_group");

            assertThat(properties.envelopes()).isEmpty();
        });
    }

    @Test
    void overriddenValuesTakePrecedenceOverDefaults() {
        runner.withPropertyValues(
                        "service-call-logging.source-header-name=X-From-Service",
                        "service-call-logging.destination-header-name=X-To-Service",
                        "service-call-logging.max-body-bytes=2048",
                        "service-call-logging.metrics.prefix=custom.calls")
                .run(context -> {
                    ServiceCallLoggingProperties properties = context.getBean(ServiceCallLoggingProperties.class);
                    assertThat(properties.sourceHeaderName()).isEqualTo("X-From-Service");
                    assertThat(properties.destinationHeaderName()).isEqualTo("X-To-Service");
                    assertThat(properties.maxBodyBytes()).isEqualTo(2048);
                    assertThat(properties.metrics().prefix()).isEqualTo("custom.calls");
                    // untouched keys keep their defaults
                    assertThat(properties.metrics().outcomeTagName()).isEqualTo("outcome");
                });
    }

    @Test
    void blankSourceHeaderNameIsRejected() {
        runner.withPropertyValues("service-call-logging.source-header-name=")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void negativeMaxBodyBytesIsRejected() {
        runner.withPropertyValues("service-call-logging.max-body-bytes=-1")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void zeroMaxBodyBytesIsAcceptedAndSkipsBodyReading() {
        runner.withPropertyValues("service-call-logging.max-body-bytes=0")
                .run(context -> assertThat(context.getBean(ServiceCallLoggingProperties.class).maxBodyBytes())
                        .isZero());
    }

    @Test
    void aConfiguredEnvelopeListBindsInOrderWithItsOwnValues() {
        runner.withPropertyValues(
                        "service-call-logging.envelopes[0].code-field=statusCode",
                        "service-call-logging.envelopes[0].message-field=message",
                        "service-call-logging.envelopes[1].code-field=responseCode",
                        "service-call-logging.envelopes[1].message-field=responseDescription",
                        "service-call-logging.envelopes[1].successful-value=1")
                .run(context -> {
                    ServiceCallLoggingProperties properties = context.getBean(ServiceCallLoggingProperties.class);

                    assertThat(properties.envelopes()).hasSize(2);
                    assertThat(properties.envelopes().get(0).codeField()).isEqualTo("statusCode");
                    assertThat(properties.envelopes().get(0).messageField()).isEqualTo("message");
                    assertThat(properties.envelopes().get(1).codeField()).isEqualTo("responseCode");
                    assertThat(properties.envelopes().get(1).messageField()).isEqualTo("responseDescription");
                    assertThat(properties.envelopes().get(1).successfulValue()).isEqualTo(1);
                });
    }

    @Test
    void aPartiallySpecifiedEnvelopeEntryFallsBackPerFieldToTheDocumentedDefaults() {
        runner.withPropertyValues("service-call-logging.envelopes[0].code-field=statusCode")
                .run(context -> {
                    ServiceCallLoggingProperties.Envelope envelope =
                            context.getBean(ServiceCallLoggingProperties.class).envelopes().get(0);

                    assertThat(envelope.codeField()).isEqualTo("statusCode");
                    assertThat(envelope.messageField()).isEqualTo("message");
                    assertThat(envelope.successfulValue()).isZero();
                });
    }

    @Test
    void anEnvelopeConstructedWithBlanksNormalisesToTheDocumentedDefaults() {
        ServiceCallLoggingProperties.Envelope blank =
                new ServiceCallLoggingProperties.Envelope("  ", null, 3);

        assertThat(blank.codeField()).isEqualTo("responseCode");
        assertThat(blank.messageField()).isEqualTo("message");
        assertThat(blank.successfulValue()).isEqualTo(3);
    }

    @Test
    void theBuiltInDefaultEnvelopeIsResponseCodeAndMessageAndZero() {
        assertThat(ServiceCallLoggingProperties.Envelope.DEFAULT.codeField()).isEqualTo("responseCode");
        assertThat(ServiceCallLoggingProperties.Envelope.DEFAULT.messageField()).isEqualTo("message");
        assertThat(ServiceCallLoggingProperties.Envelope.DEFAULT.successfulValue()).isZero();
    }

    // ===== spec 003 (T005) — the four new metrics keys =====

    @Test
    void theFourNewMetricsKeysBindToTheirDocumentedDefaults() {
        runner.run(context -> {
            ServiceCallLoggingProperties.Metrics metrics =
                    context.getBean(ServiceCallLoggingProperties.class).metrics();

            assertThat(metrics.destinationUriTagName()).isEqualTo("destination_uri");
            assertThat(metrics.inboundUriTagName()).isEqualTo("inbound_uri");
            assertThat(metrics.operationTagName()).isEqualTo("operation");
            assertThat(metrics.latencyBuckets())
                    .as("unset means delegate to the metrics library (FR-012)")
                    .isEmpty();
        });
    }

    @Test
    void theFourNewMetricsKeysBindExplicitValues() {
        runner.withPropertyValues(
                        "service-call-logging.metrics.destination-uri-tag-name=dest_path",
                        "service-call-logging.metrics.inbound-uri-tag-name=in_path",
                        "service-call-logging.metrics.operation-tag-name=biz_op",
                        "service-call-logging.metrics.latency-buckets=50ms,200ms,1s")
                .run(context -> {
                    ServiceCallLoggingProperties.Metrics metrics =
                            context.getBean(ServiceCallLoggingProperties.class).metrics();

                    assertThat(metrics.destinationUriTagName()).isEqualTo("dest_path");
                    assertThat(metrics.inboundUriTagName()).isEqualTo("in_path");
                    assertThat(metrics.operationTagName()).isEqualTo("biz_op");
                    assertThat(metrics.latencyBuckets()).containsExactly("50ms", "200ms", "1s");
                    assertThat(metrics.latencyBucketDurations()).containsExactly(
                            Duration.ofMillis(50), Duration.ofMillis(200), Duration.ofSeconds(1));
                });
    }

    @Test
    void blankNewTagNamesAreRejected() {
        runner.withPropertyValues("service-call-logging.metrics.destination-uri-tag-name=")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("service-call-logging.metrics.inbound-uri-tag-name= ")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("service-call-logging.metrics.operation-tag-name=")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void unparseableAndNonPositiveBucketValuesAreDiscardedNotRejected() {
        // FR-014 — a bad boundary costs a bucket, never a startup.
        runner.withPropertyValues(
                        "service-call-logging.metrics.latency-buckets=not-a-duration,0s,-5ms,250ms")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ServiceCallLoggingProperties.Metrics metrics =
                            context.getBean(ServiceCallLoggingProperties.class).metrics();

                    assertThat(metrics.latencyBuckets()).hasSize(4);
                    assertThat(metrics.latencyBucketDurations())
                            .containsExactly(Duration.ofMillis(250));
                });
    }

    @Test
    void anAbsentLatencyBucketListNormalisesToEmptyRatherThanNull() {
        ServiceCallLoggingProperties.Metrics metrics = new ServiceCallLoggingProperties.Metrics(
                "http.outbound.calls", "destination", "outcome", "http_status_group",
                "destination_uri", "inbound_uri", "operation", null);

        assertThat(metrics.latencyBuckets()).isNotNull().isEmpty();
        assertThat(metrics.latencyBucketDurations()).isNotNull().isEmpty();
    }

    @Test
    void theOperationHeaderNameIsNotConfigurable() {
        // FR-018 — the operation header is a per-call caller input, not a per-service identity.
        // No property may change it; an attempt to set one must bind nothing and have no effect.
        runner.withPropertyValues("service-call-logging.operation-header-name=X-Custom-Operation")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ServiceCallLoggingProperties properties =
                            context.getBean(ServiceCallLoggingProperties.class);
                    assertThat(properties.getClass().getRecordComponents())
                            .as("no record component may expose an operation header name")
                            .noneMatch(c -> c.getName().toLowerCase().contains("operationheader"));
                });
    }
}
