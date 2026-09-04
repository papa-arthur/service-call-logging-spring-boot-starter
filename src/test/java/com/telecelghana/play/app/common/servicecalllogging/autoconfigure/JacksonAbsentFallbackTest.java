package com.telecelghana.play.app.common.servicecalllogging.autoconfigure;

import com.telecelghana.play.app.common.servicecalllogging.ResponseCodeExtractor;
import com.telecelghana.play.app.common.servicecalllogging.filter.OutboundCallExchangeFilter;
import com.telecelghana.play.app.common.servicecalllogging.interceptor.OutboundCallInterceptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.client.RestTemplateAutoConfiguration;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Constitution Principle II — the starter must still work for a consumer with no Jackson.
 *
 * <p>Without Jackson there is no default {@code ResponseCodeExtractor} bean, so both instrumentation
 * configurations fall back to an inline extractor that reports nothing. That fallback is a real
 * degradation path, and until this test it was the only uncovered code in the auto-configuration
 * package — surfaced by the coverage gate Principle III/V requires, which is exactly what that gate
 * is for.
 *
 * <p>The point is not merely that the beans exist: it is that a consumer in this shape still gets a
 * working, non-throwing instrumentation path that degrades to {@code responseCode=absent} rather
 * than failing to start.
 */
class JacksonAbsentFallbackTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ServiceCallLoggingAutoConfiguration.class))
            .withClassLoader(new FilteredClassLoader(ObjectMapper.class))
            .withPropertyValues("spring.application.name=my-service");

    @Test
    void theBlockingPathStillStartsAndIsInstrumentedWithNoJackson() {
        this.runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(OutboundCallInterceptor.class);
            assertThat(context)
                    .as("no Jackson means no default extractor bean; the fallback is inline")
                    .doesNotHaveBean(ResponseCodeExtractor.class);
        });
    }

    @Test
    void theReactivePathStillStartsAndIsInstrumentedWithNoJackson() {
        this.runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(OutboundCallExchangeFilter.class);
        });
    }

    @Test
    void aRealCallWithNoJacksonDegradesToAbsentInsteadOfThrowing() throws Exception {
        // Drives the fallback extractor's body, not just its creation: only an actual call through
        // the auto-configured interceptor executes it. Proves the Jackson-free consumer gets a
        // completed call with responseCode=absent rather than an exception.
        try (com.telecelghana.play.app.common.servicecalllogging.testsupport.StubHttpServer server =
                     new com.telecelghana.play.app.common.servicecalllogging.testsupport.StubHttpServer()) {
            server.respondWith(200, "{\"responseCode\":0,\"message\":\"OK\"}");
            java.util.List<com.telecelghana.play.app.common.servicecalllogging.logging.OutboundCallRecord> records =
                    new java.util.ArrayList<>();

            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(
                            RestTemplateAutoConfiguration.class,
                            ServiceCallLoggingAutoConfiguration.class))
                    .withClassLoader(new FilteredClassLoader(ObjectMapper.class))
                    .withPropertyValues("spring.application.name=my-service")
                    .withBean(com.telecelghana.play.app.common.servicecalllogging.logging.CallLogger.class,
                            () -> new com.telecelghana.play.app.common.servicecalllogging.logging.CallLogger() {
                                @Override
                                public void log(com.telecelghana.play.app.common.servicecalllogging.logging.OutboundCallRecord record) {
                                    records.add(record);
                                }
                            })
                    .run(context -> {
                        assertThat(context).hasNotFailed();

                        String body = context.getBean(RestTemplateBuilder.class).build()
                                .getForObject(server.url("/x"), String.class);

                        assertThat(body)
                                .as("the response still reaches the caller intact")
                                .contains("\"responseCode\":0");
                        assertThat(records).hasSize(1);
                        assertThat(records.get(0).responseCode())
                                .as("no extractor available, so the code degrades to absent")
                                .isNull();
                    });
        }
    }

    @Test
    void aRealReactiveCallWithNoJacksonAlsoDegradesToAbsent() throws Exception {
        // The reactive twin of the test above — the filter has its own fallback lambda, so the
        // blocking-path proof says nothing about this one.
        try (com.telecelghana.play.app.common.servicecalllogging.testsupport.StubHttpServer server =
                     new com.telecelghana.play.app.common.servicecalllogging.testsupport.StubHttpServer()) {
            server.respondWith(200, "{\"responseCode\":0,\"message\":\"OK\"}");
            java.util.List<com.telecelghana.play.app.common.servicecalllogging.logging.OutboundCallRecord> records =
                    new java.util.ArrayList<>();

            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(
                            org.springframework.boot.autoconfigure.web.reactive.function.client
                                    .WebClientAutoConfiguration.class,
                            ServiceCallLoggingAutoConfiguration.class))
                    .withClassLoader(new FilteredClassLoader(ObjectMapper.class))
                    .withPropertyValues("spring.application.name=my-service")
                    .withBean(com.telecelghana.play.app.common.servicecalllogging.logging.CallLogger.class,
                            () -> new com.telecelghana.play.app.common.servicecalllogging.logging.CallLogger() {
                                @Override
                                public void log(com.telecelghana.play.app.common.servicecalllogging.logging.OutboundCallRecord record) {
                                    records.add(record);
                                }
                            })
                    .run(context -> {
                        assertThat(context).hasNotFailed();

                        String body = context.getBean(
                                        org.springframework.web.reactive.function.client.WebClient.Builder.class)
                                .build().get().uri(server.url("/x"))
                                .retrieve().bodyToMono(String.class)
                                .block(java.time.Duration.ofSeconds(20));

                        assertThat(body).contains("\"responseCode\":0");
                        assertThat(records).hasSize(1);
                        assertThat(records.get(0).responseCode()).isNull();
                    });
        }
    }
}
