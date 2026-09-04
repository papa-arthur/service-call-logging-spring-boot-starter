package com.telecelghana.play.app.common.servicecalllogging.autoconfigure;

import com.telecelghana.play.app.common.servicecalllogging.resolver.DestinationNameResolver;
import com.telecelghana.play.app.common.servicecalllogging.testsupport.StubHttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.client.RestTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-001 — a consumer that never set {@code spring.application.name} must still start, must be
 * told about it once at startup, and must report {@code unknown} on the wire.
 */
@ExtendWith(OutputCaptureExtension.class)
class SourceNameWarningTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    RestTemplateAutoConfiguration.class,
                    ServiceCallLoggingAutoConfiguration.class));

    @Test
    void aMissingApplicationNameProducesAStartupWarning(CapturedOutput output) {
        this.runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(DestinationNameResolver.class).getSourceName()).isEqualTo("unknown");
        });

        assertThat(output).contains("WARN");
        assertThat(output).contains("spring.application.name is not configured");
        assertThat(output).contains("unknown");
    }

    @Test
    void aConfiguredApplicationNameProducesNoWarning(CapturedOutput output) {
        this.runner.withPropertyValues("spring.application.name=my-service").run(context ->
                assertThat(context.getBean(DestinationNameResolver.class).getSourceName())
                        .isEqualTo("my-service"));

        assertThat(output).doesNotContain("spring.application.name is not configured");
    }

    @Test
    void theUnknownSourceNameIsWhatActuallyGoesOnTheWire() throws Exception {
        try (StubHttpServer server = new StubHttpServer()) {
            server.respondWith(200, "{\"responseCode\":0}");

            this.runner.run(context -> {
                RestTemplate restTemplate = context.getBean(RestTemplateBuilder.class).build();
                restTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
                    @Override
                    public boolean hasError(org.springframework.http.client.ClientHttpResponse response) {
                        return false;
                    }
                });

                restTemplate.getForObject(server.url("/x"), String.class);

                assertThat(server.lastRequestHeader("X-Source-Service")).isEqualTo("unknown");
            });
        }
    }
}
