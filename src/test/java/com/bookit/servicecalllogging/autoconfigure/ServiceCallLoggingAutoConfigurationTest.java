package com.bookit.servicecalllogging.autoconfigure;

import com.bookit.servicecalllogging.ResponseCodeExtractor;
import com.bookit.servicecalllogging.ServiceCallLoggingProperties;
import com.bookit.servicecalllogging.extractor.JacksonResponseCodeExtractor;
import com.bookit.servicecalllogging.filter.OutboundCallExchangeFilter;
import com.bookit.servicecalllogging.interceptor.OutboundCallInterceptor;
import com.bookit.servicecalllogging.logging.CallLogger;
import com.bookit.servicecalllogging.resolver.DestinationNameResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The auto-configuration matrix required at 100% coverage by Constitution Principle V:
 * capability present/absent x starter enabled/disabled x consumer override present/absent.
 */
class ServiceCallLoggingAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ServiceCallLoggingAutoConfiguration.class));

    @Test
    void theDefaultConfigurationRegistersEveryCoreBean() {
        this.runner.withPropertyValues("spring.application.name=my-service").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ServiceCallLoggingProperties.class);
            assertThat(context).hasSingleBean(DestinationNameResolver.class);
            assertThat(context).hasSingleBean(CallLogger.class);
            assertThat(context).hasSingleBean(ResponseCodeExtractor.class);
            assertThat(context).hasSingleBean(OutboundCallInterceptor.class);
            assertThat(context).hasSingleBean(OutboundCallExchangeFilter.class);
            assertThat(context).hasBean("serviceCallLoggingRestTemplateCustomizer");
            assertThat(context).hasBean("serviceCallLoggingWebClientCustomizer");
        });
    }

    @Test
    void theSourceNameComesFromSpringApplicationName() {
        this.runner.withPropertyValues("spring.application.name=billing-service").run(context ->
                assertThat(context.getBean(DestinationNameResolver.class).getSourceName())
                        .isEqualTo("billing-service"));
    }

    @Test
    void anAbsentApplicationNameDegradesToUnknownWithoutFailingStartup() {
        this.runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(DestinationNameResolver.class).getSourceName()).isEqualTo("unknown");
        });
    }

    @Test
    void disablingTheStarterRegistersNoBeansAtAll() {
        this.runner.withPropertyValues("service-call-logging.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ServiceCallLoggingProperties.class);
            assertThat(context).doesNotHaveBean(DestinationNameResolver.class);
            assertThat(context).doesNotHaveBean(CallLogger.class);
            assertThat(context).doesNotHaveBean(ResponseCodeExtractor.class);
            assertThat(context).doesNotHaveBean(OutboundCallInterceptor.class);
            assertThat(context).doesNotHaveBean(OutboundCallExchangeFilter.class);
        });
    }

    @Test
    void explicitlyEnablingTheStarterIsEquivalentToTheDefault() {
        this.runner.withPropertyValues("service-call-logging.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(OutboundCallInterceptor.class);
            assertThat(context).hasSingleBean(OutboundCallExchangeFilter.class);
        });
    }

    @Test
    void theDefaultExtractorIsTheJacksonOneWhenJacksonIsPresent() {
        this.runner.run(context ->
                assertThat(context.getBean(ResponseCodeExtractor.class))
                        .isInstanceOf(JacksonResponseCodeExtractor.class));
    }

    @Test
    void withoutJacksonTheStarterStillStartsAndDegradesToAbsent() {
        this.runner.withClassLoader(new FilteredClassLoader(ObjectMapper.class)).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ResponseCodeExtractor.class);
            // the instrumentation beans still exist; they simply report responseCode=absent
            assertThat(context).hasSingleBean(OutboundCallInterceptor.class);
            assertThat(context).hasSingleBean(OutboundCallExchangeFilter.class);
        });
    }

    @Test
    void aConsumerSuppliedResolverAndLoggerReplaceTheStarterBeans() {
        this.runner.withUserConfiguration(OverridesConfig.class).run(context -> {
            assertThat(context).hasSingleBean(DestinationNameResolver.class);
            assertThat(context.getBean(DestinationNameResolver.class))
                    .isSameAs(context.getBean("myResolver"));
            assertThat(context).hasSingleBean(CallLogger.class);
            assertThat(context.getBean(CallLogger.class)).isSameAs(context.getBean("myLogger"));
        });
    }

    @Test
    void neitherHttpClientOnTheClasspathStillLeavesAWorkingContext() {
        this.runner.withClassLoader(new FilteredClassLoader(
                        org.springframework.web.client.RestTemplate.class,
                        org.springframework.web.reactive.function.client.WebClient.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    // no instrumentation is possible, but the core beans exist and startup succeeded
                    assertThat(context).hasSingleBean(DestinationNameResolver.class);
                    assertThat(context).hasSingleBean(CallLogger.class);
                    assertThat(context).doesNotHaveBean(OutboundCallInterceptor.class);
                    assertThat(context).doesNotHaveBean(OutboundCallExchangeFilter.class);
                });
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class OverridesConfig {
        @org.springframework.context.annotation.Bean
        DestinationNameResolver myResolver() {
            return new DestinationNameResolver("overridden");
        }

        @org.springframework.context.annotation.Bean
        CallLogger myLogger() {
            return new CallLogger();
        }
    }
}
