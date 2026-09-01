package com.bookit.servicecalllogging;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

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
}
