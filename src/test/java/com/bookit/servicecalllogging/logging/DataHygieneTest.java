package com.bookit.servicecalllogging.logging;

import com.bookit.servicecalllogging.autoconfigure.ServiceCallLoggingAutoConfiguration;
import com.bookit.servicecalllogging.testsupport.StubHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.client.RestTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.web.reactive.function.client.WebClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Constitution Principle VII — a request carrying credentials must produce log output that
 * mentions neither the header names nor any part of their values.
 */
@ExtendWith(OutputCaptureExtension.class)
class DataHygieneTest {

    private static final String SECRET = "supersecrettokenvalue";
    private static final String SESSION = "sessionid=abc123secret";

    private StubHttpServer server;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    RestTemplateAutoConfiguration.class,
                    WebClientAutoConfiguration.class,
                    ServiceCallLoggingAutoConfiguration.class))
            .withPropertyValues("spring.application.name=my-service");

    @BeforeEach
    void setUp() throws IOException {
        this.server = new StubHttpServer();
    }

    @AfterEach
    void tearDown() {
        this.server.close();
    }

    private static void assertNothingSensitiveWasLogged(CapturedOutput output) {
        assertThat(output).doesNotContain(SECRET);
        assertThat(output).doesNotContain(SESSION);
        assertThat(output).doesNotContain("abc123secret");
        assertThat(output).doesNotContain("Authorization");
        assertThat(output).doesNotContain("Cookie");
        assertThat(output).doesNotContain("Bearer");
    }

    @Test
    void credentialsOnTheBlockingPathNeverReachTheLog(CapturedOutput output) {
        this.server.respondWith(200, "{\"responseCode\":0}");

        this.runner.run(context -> {
            RestTemplate restTemplate = context.getBean(RestTemplateBuilder.class).build();
            restTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
                @Override
                public boolean hasError(org.springframework.http.client.ClientHttpResponse response) {
                    return false;
                }
            });

            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.AUTHORIZATION, "Bearer " + SECRET);
            headers.add(HttpHeaders.COOKIE, SESSION);

            restTemplate.exchange(this.server.url("/charge"), HttpMethod.GET,
                    new HttpEntity<>(headers), String.class);

            // the call really did carry the credentials on the wire
            assertThat(this.server.lastRequestHeader("Authorization")).contains(SECRET);
        });

        // ...yet the starter's log line mentions none of it
        assertThat(output).contains("outbound-req-response source=my-service");
        assertNothingSensitiveWasLogged(output);
    }

    @Test
    void credentialsOnTheReactivePathNeverReachTheLog(CapturedOutput output) {
        this.server.respondWith(200, "{\"responseCode\":0}");

        this.runner.run(context -> {
            context.getBean(WebClient.Builder.class).build()
                    .get().uri(this.server.url("/charge"))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + SECRET)
                    .header(HttpHeaders.COOKIE, SESSION)
                    .retrieve().bodyToMono(String.class).block(Duration.ofSeconds(20));

            assertThat(this.server.lastRequestHeader("Authorization")).contains(SECRET);
        });

        assertThat(output).contains("outbound-req-response source=my-service");
        assertNothingSensitiveWasLogged(output);
    }

    @Test
    void aSensitiveResponseBodyIsNeverLoggedEither(CapturedOutput output) {
        this.server.respondWith(200,
                "{\"responseCode\":0,\"accessToken\":\"" + SECRET + "\",\"ssn\":\"123-45-6789\"}");

        this.runner.run(context -> {
            RestTemplate restTemplate = context.getBean(RestTemplateBuilder.class).build();
            restTemplate.getForObject(this.server.url("/x"), String.class);
        });

        assertThat(output).contains("responseCode=0");
        assertThat(output).doesNotContain(SECRET);
        assertThat(output).doesNotContain("123-45-6789");
        assertThat(output).doesNotContain("accessToken");
    }

    @Test
    void anInstrumentationWarningStillLeaksNothing(CapturedOutput output) {
        CallLogger callLogger = new CallLogger();

        callLogger.logWarn("my-service", "payments:8080",
                new IllegalStateException("failed while handling request"));

        assertThat(output).contains("outbound-call-instrumentation-error");
        assertNothingSensitiveWasLogged(output);
    }
}
