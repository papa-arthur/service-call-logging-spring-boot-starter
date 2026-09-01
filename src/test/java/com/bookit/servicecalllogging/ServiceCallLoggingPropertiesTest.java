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
    void allNineKeysBindToTheirDocumentedDefaults() {
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
}
