package com.bookit.servicecalllogging.autoconfigure;

import com.bookit.servicecalllogging.EnvelopeFieldExtractor;
import com.bookit.servicecalllogging.EnvelopeMatch;
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
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The auto-configuration matrix required at 100% coverage by Constitution Principle V:
 * capability present/absent x starter enabled/disabled x consumer override present/absent.
 */
class ServiceCallLoggingAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ServiceCallLoggingAutoConfiguration.class));

    /**
     * Reads a private field directly: the only way to prove a bean was actually wired into the
     * interceptor's/filter's constructor, as opposed to merely being present in the registry
     * (spec 003 remediation, T062 — see the tests below that use this).
     */
    private static Object fieldValue(Object target, String fieldName) {
        return ReflectionTestUtils.getField(target, fieldName);
    }

    @Test
    void theDefaultConfigurationRegistersEveryCoreBean() {
        this.runner.withPropertyValues("spring.application.name=my-service").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ServiceCallLoggingProperties.class);
            assertThat(context).hasSingleBean(DestinationNameResolver.class);
            assertThat(context).hasSingleBean(CallLogger.class);
            assertThat(context).hasSingleBean(ResponseCodeExtractor.class);
            assertThat(context).hasSingleBean(EnvelopeFieldExtractor.class);
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
            assertThat(context).doesNotHaveBean(EnvelopeFieldExtractor.class);
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
            assertThat(context).doesNotHaveBean(EnvelopeFieldExtractor.class);
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

    @Test
    void theEnvelopeFieldExtractorIsNotRegisteredWhenJacksonIsAbsent() {
        this.runner.withClassLoader(new FilteredClassLoader(ObjectMapper.class)).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(EnvelopeFieldExtractor.class);
            // ...and the rest of the starter still comes up, per Principle II
            assertThat(context).hasSingleBean(CallLogger.class);
            assertThat(context).hasSingleBean(OutboundCallInterceptor.class);
        });
    }

    @Test
    void aConsumerEnvelopeFieldExtractorReplacesTheStarterOne() {
        EnvelopeFieldExtractor custom = bytes -> EnvelopeMatch.NONE;

        this.runner.withBean(EnvelopeFieldExtractor.class, () -> custom).run(context -> {
            assertThat(context).hasSingleBean(EnvelopeFieldExtractor.class);
            assertThat(context.getBean(EnvelopeFieldExtractor.class)).isSameAs(custom);
        });
    }

    @Test
    void theEnvelopeFieldExtractorIsStillRegisteredWhenAConsumerOverridesTheResponseCodeExtractor() {
        // FR-016: message extraction is independent of who supplies the code
        ResponseCodeExtractor customCode = bytes -> java.util.Optional.of(0);

        this.runner.withBean(ResponseCodeExtractor.class, () -> customCode).run(context -> {
            assertThat(context.getBean(ResponseCodeExtractor.class)).isSameAs(customCode);
            assertThat(context).doesNotHaveBean(JacksonResponseCodeExtractor.class);
            assertThat(context).hasSingleBean(EnvelopeFieldExtractor.class);
        });
    }

    @Test
    void aConfiguredEnvelopeListReachesTheExtractorBean() {
        this.runner.withPropertyValues(
                        "service-call-logging.envelopes[0].code-field=statusCode",
                        "service-call-logging.envelopes[0].message-field=detail")
                .run(context -> {
                    EnvelopeMatch match = context.getBean(EnvelopeFieldExtractor.class)
                            .extract("{\"statusCode\":4,\"detail\":\"nope\"}"
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));

                    assertThat(match.rawCode()).isEqualTo(4);
                    assertThat(match.message()).isEqualTo("nope");
                });
    }

    // ===== spec 003 (T025) — the two URI resolver beans across the full matrix =====

    @Test
    void bothUriResolversActivateWhenTheStarterIsEnabled() {
        this.runner.withPropertyValues("spring.application.name=my-service").run(context -> {
            assertThat(context).hasSingleBean(
                    com.bookit.servicecalllogging.uri.DestinationUriResolver.class);
            assertThat(context).hasSingleBean(
                    com.bookit.servicecalllogging.uri.InboundUriResolver.class);
        });
    }

    @Test
    void neitherUriResolverActivatesWhenTheStarterIsDisabled() {
        this.runner.withPropertyValues("service-call-logging.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(
                    com.bookit.servicecalllogging.uri.DestinationUriResolver.class);
            assertThat(context).doesNotHaveBean(
                    com.bookit.servicecalllogging.uri.InboundUriResolver.class);
        });
    }

    @Test
    void aConsumerSuppliedDestinationUriResolverReplacesTheStarterOne() {
        this.runner.withUserConfiguration(CustomUriResolvers.class).run(context -> {
            assertThat(context).hasSingleBean(
                    com.bookit.servicecalllogging.uri.DestinationUriResolver.class);
            Object override = context.getBean("myDestinationUriResolver");
            assertThat(context.getBean(com.bookit.servicecalllogging.uri.DestinationUriResolver.class))
                    .isSameAs(override);
            // Registry presence alone does not prove Principle III: the override is meaningless
            // if the interceptor/filter never actually receive it (spec 003 remediation, T062).
            assertThat(fieldValue(context.getBean(OutboundCallInterceptor.class), "destinationUriResolver"))
                    .isSameAs(override);
            assertThat(fieldValue(context.getBean(OutboundCallExchangeFilter.class), "destinationUriResolver"))
                    .isSameAs(override);
        });
    }

    @Test
    void aConsumerSuppliedInboundUriResolverReplacesTheStarterOne() {
        this.runner.withUserConfiguration(CustomUriResolvers.class).run(context -> {
            assertThat(context).hasSingleBean(
                    com.bookit.servicecalllogging.uri.InboundUriResolver.class);
            Object override = context.getBean("myInboundUriResolver");
            assertThat(context.getBean(com.bookit.servicecalllogging.uri.InboundUriResolver.class))
                    .isSameAs(override);
            assertThat(fieldValue(context.getBean(OutboundCallInterceptor.class), "inboundUriResolver"))
                    .isSameAs(override);
            assertThat(fieldValue(context.getBean(OutboundCallExchangeFilter.class), "inboundUriResolver"))
                    .isSameAs(override);
        });
    }

    // ===== spec 003 (T035) — the OperationResolver bean across the full matrix =====

    @Test
    void theOperationResolverActivatesWhenTheStarterIsEnabled() {
        this.runner.withPropertyValues("spring.application.name=my-service").run(context ->
                assertThat(context).hasSingleBean(
                        com.bookit.servicecalllogging.operation.OperationResolver.class));
    }

    @Test
    void theOperationResolverDoesNotActivateWhenTheStarterIsDisabled() {
        this.runner.withPropertyValues("service-call-logging.enabled=false").run(context ->
                assertThat(context).doesNotHaveBean(
                        com.bookit.servicecalllogging.operation.OperationResolver.class));
    }

    @Test
    void aConsumerSuppliedOperationResolverReplacesTheStarterOne() {
        this.runner.withUserConfiguration(CustomOperationResolver.class).run(context -> {
            assertThat(context).hasSingleBean(
                    com.bookit.servicecalllogging.operation.OperationResolver.class);
            Object override = context.getBean("myOperationResolver");
            assertThat(context.getBean(com.bookit.servicecalllogging.operation.OperationResolver.class))
                    .isSameAs(override);
            assertThat(fieldValue(context.getBean(OutboundCallInterceptor.class), "operationResolver"))
                    .isSameAs(override);
            assertThat(fieldValue(context.getBean(OutboundCallExchangeFilter.class), "operationResolver"))
                    .isSameAs(override);
        });
    }

    @Test
    void theOperationResolverIsASingletonSoOneCapCoversTheWholeApplication() {
        // The distinct-value cap is per instance, so a fresh resolver per client would mean a
        // fresh, separately-exhaustible cap per client rather than one bound for the application.
        // Checking only registry-level singleton-ness would not have caught spec 003's shipped
        // defect (T062): the bean was already a singleton in the registry while the interceptor
        // and filter each silently constructed their own private resolver instead of using it.
        this.runner.withPropertyValues("spring.application.name=my-service").run(context -> {
            com.bookit.servicecalllogging.operation.OperationResolver shared =
                    context.getBean(com.bookit.servicecalllogging.operation.OperationResolver.class);

            assertThat(fieldValue(context.getBean(OutboundCallInterceptor.class), "operationResolver"))
                    .isSameAs(shared);
            assertThat(fieldValue(context.getBean(OutboundCallExchangeFilter.class), "operationResolver"))
                    .isSameAs(shared);
        });
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class CustomOperationResolver {

        @org.springframework.context.annotation.Bean
        com.bookit.servicecalllogging.operation.OperationResolver myOperationResolver() {
            return new com.bookit.servicecalllogging.operation.OperationResolver();
        }
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class CustomUriResolvers {

        @org.springframework.context.annotation.Bean
        com.bookit.servicecalllogging.uri.DestinationUriResolver myDestinationUriResolver() {
            return new com.bookit.servicecalllogging.uri.DestinationUriResolver();
        }

        @org.springframework.context.annotation.Bean
        com.bookit.servicecalllogging.uri.InboundUriResolver myInboundUriResolver() {
            return new com.bookit.servicecalllogging.uri.InboundUriResolver();
        }
    }
}
