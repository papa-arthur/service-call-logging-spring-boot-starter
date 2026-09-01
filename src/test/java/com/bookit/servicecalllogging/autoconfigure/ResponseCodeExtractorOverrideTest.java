package com.bookit.servicecalllogging.autoconfigure;

import com.bookit.servicecalllogging.ResponseCodeExtractor;
import com.bookit.servicecalllogging.extractor.JacksonResponseCodeExtractor;
import com.bookit.servicecalllogging.logging.CallLogger;
import com.bookit.servicecalllogging.testsupport.RecordingCallLogger;
import com.bookit.servicecalllogging.testsupport.StubHttpServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.client.RestTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-022 — the {@code responseCode} extraction behaviour must be replaceable, and the default
 * must then not be instantiated at all.
 */
class ResponseCodeExtractorOverrideTest {

    private StubHttpServer server;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    RestTemplateAutoConfiguration.class,
                    ServiceCallLoggingAutoConfiguration.class))
            .withPropertyValues("spring.application.name=my-service");

    /** A consumer whose envelope nests the code under a {@code data} object. */
    @Configuration(proxyBeanMethods = false)
    static class NestedExtractorConfig {
        @Bean
        ResponseCodeExtractor nestedResponseCodeExtractor() {
            ObjectMapper mapper = new ObjectMapper();
            return bodyBytes -> {
                try {
                    JsonNode code = mapper.readTree(bodyBytes).path("data").path("responseCode");
                    return code.isInt() ? Optional.of(code.intValue()) : Optional.empty();
                } catch (Exception ex) {
                    return Optional.empty();
                }
            };
        }

        @Bean
        CallLogger callLogger() {
            return new RecordingCallLogger();
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        this.server = new StubHttpServer();
    }

    @AfterEach
    void tearDown() {
        this.server.close();
    }

    @Test
    void aConsumerExtractorReplacesTheDefaultAndTheDefaultIsNotCreated() {
        this.runner.withUserConfiguration(NestedExtractorConfig.class).run(context -> {
            assertThat(context).hasSingleBean(ResponseCodeExtractor.class);
            assertThat(context.getBean(ResponseCodeExtractor.class))
                    .isSameAs(context.getBean("nestedResponseCodeExtractor"));
            assertThat(context.getBean(ResponseCodeExtractor.class))
                    .isNotInstanceOf(JacksonResponseCodeExtractor.class);
            assertThat(context).doesNotHaveBean("jacksonResponseCodeExtractor");
        });
    }

    @Test
    void theInterceptorActuallyUsesTheConsumerExtractor() {
        this.server.respondWith(200, "{\"data\":{\"responseCode\":0,\"message\":\"OK\"}}");

        this.runner.withUserConfiguration(NestedExtractorConfig.class).run(context -> {
            context.getBean(RestTemplateBuilder.class).build()
                    .getForObject(this.server.url("/x"), String.class);

            // the default extractor would have found no top-level responseCode and logged absent
            assertThat(context.getBean(RecordingCallLogger.class).onlyRecord().responseCode())
                    .isEqualTo(0);
        });
    }

    @Test
    void theDefaultExtractorWouldNotHaveFoundTheNestedCode() {
        this.server.respondWith(200, "{\"data\":{\"responseCode\":0}}");

        this.runner.withUserConfiguration(RecordingLoggerOnlyConfig.class).run(context -> {
            context.getBean(RestTemplateBuilder.class).build()
                    .getForObject(this.server.url("/x"), String.class);

            assertThat(context.getBean(RecordingCallLogger.class).onlyRecord().responseCode()).isNull();
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class RecordingLoggerOnlyConfig {
        @Bean
        CallLogger callLogger() {
            return new RecordingCallLogger();
        }
    }

    @Test
    void aConsumerExtractorStillGetsTheMessageFromTheStartersOwnFieldMatching() {
        // FR-016 / Clarification 2026-09-01: the custom bean supplies the code; the starter
        // independently supplies the message, with no change to the consumer's extractor.
        this.server.respondWith(200, "{\"data\":{\"responseCode\":0},\"message\":\"All good\"}");

        this.runner.withUserConfiguration(NestedExtractorConfig.class).run(context -> {
            context.getBean(RestTemplateBuilder.class).build()
                    .getForObject(this.server.url("/x"), String.class);

            var record = context.getBean(RecordingCallLogger.class).onlyRecord();
            assertThat(record.responseCode()).isEqualTo(0);
            assertThat(record.message()).isEqualTo("All good");
        });
    }

    @Test
    void aConsumerExtractorIsClassifiedAgainstTheConfiguredSuccessfulValue() {
        // The custom bean returns 1; a configured combination says 1 means success.
        this.server.respondWith(200, "{\"data\":{\"responseCode\":1},\"responseCode\":1,\"message\":\"OK\"}");

        this.runner.withUserConfiguration(NestedExtractorConfig.class)
                .withPropertyValues(
                        "service-call-logging.envelopes[0].code-field=responseCode",
                        "service-call-logging.envelopes[0].successful-value=1")
                .run(context -> {
                    context.getBean(RestTemplateBuilder.class).build()
                            .getForObject(this.server.url("/x"), String.class);

                    assertThat(context.getBean(RecordingCallLogger.class).onlyRecord().responseCode())
                            .isEqualTo(1);
                });
    }
}
