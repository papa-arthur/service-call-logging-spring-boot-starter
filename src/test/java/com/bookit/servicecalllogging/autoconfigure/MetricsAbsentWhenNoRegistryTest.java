package com.bookit.servicecalllogging.autoconfigure;

import com.bookit.servicecalllogging.filter.OutboundCallExchangeFilter;
import com.bookit.servicecalllogging.interceptor.OutboundCallInterceptor;
import com.bookit.servicecalllogging.metrics.OutboundCallMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Constitution Principle II — Actuator is not a required dependency. Without a
 * {@link MeterRegistry} the metrics bean simply does not exist, and instrumentation continues
 * to log normally.
 */
class MetricsAbsentWhenNoRegistryTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ServiceCallLoggingAutoConfiguration.class))
            .withPropertyValues("spring.application.name=my-service");

    @Configuration(proxyBeanMethods = false)
    static class MeterRegistryConfig {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Test
    void withNoMeterRegistryTheMetricsBeanIsAbsentButInstrumentationStillWorks() {
        this.runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(MeterRegistry.class);
            assertThat(context).doesNotHaveBean(OutboundCallMetrics.class);
            // the instrumentation beans are unaffected — metrics are an optional collaborator
            assertThat(context).hasSingleBean(OutboundCallInterceptor.class);
            assertThat(context).hasSingleBean(OutboundCallExchangeFilter.class);
        });
    }

    @Test
    void withAMeterRegistryTheMetricsBeanIsCreated() {
        this.runner.withUserConfiguration(MeterRegistryConfig.class).run(context -> {
            assertThat(context).hasSingleBean(OutboundCallMetrics.class);
            assertThat(context).hasSingleBean(OutboundCallInterceptor.class);
        });
    }

    @Test
    void disablingTheStarterAlsoSuppressesTheMetricsBean() {
        this.runner.withUserConfiguration(MeterRegistryConfig.class)
                .withPropertyValues("service-call-logging.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(OutboundCallMetrics.class));
    }

    @Test
    void aConsumerSuppliedMetricsBeanReplacesTheStarterOne() {
        this.runner.withUserConfiguration(MeterRegistryConfig.class, CustomMetricsConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(OutboundCallMetrics.class);
                    assertThat(context.getBean(OutboundCallMetrics.class))
                            .isSameAs(context.getBean("myMetrics"));
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomMetricsConfig {
        @Bean
        OutboundCallMetrics myMetrics(MeterRegistry registry,
                                     com.bookit.servicecalllogging.ServiceCallLoggingProperties properties) {
            return new OutboundCallMetrics(registry, properties);
        }
    }
}
