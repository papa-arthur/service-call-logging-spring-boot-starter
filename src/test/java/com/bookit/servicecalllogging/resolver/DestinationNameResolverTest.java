package com.bookit.servicecalllogging.resolver;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class DestinationNameResolverTest {

    private final DestinationNameResolver resolver = new DestinationNameResolver("my-service");

    @Test
    void hintHeaderValueWinsOverUrlDerivation() {
        String destination = resolver.resolve(URI.create("https://payment-service.internal:8080/api/charge"),
                "payments");

        assertThat(destination).isEqualTo("payments");
    }

    @Test
    void blankHintHeaderValueFallsBackToUrlDerivation() {
        assertThat(resolver.resolve(URI.create("http://svc:9000/x"), "   ")).isEqualTo("svc:9000");
        assertThat(resolver.resolve(URI.create("http://svc:9000/x"), null)).isEqualTo("svc:9000");
    }

    @Test
    void derivesHostAndPortFromTheUrlWhenNoHintIsPresent() {
        assertThat(resolver.resolve(URI.create("https://payment-service.internal:8080/api/charge"), null))
                .isEqualTo("payment-service.internal:8080");
    }

    @Test
    void ipAddressHostIsUsedVerbatim() {
        assertThat(resolver.resolve(URI.create("http://192.168.1.100:8080/api"), null))
                .isEqualTo("192.168.1.100:8080");
    }

    @Test
    void missingPortDefaultsToEightyForHttp() {
        assertThat(resolver.resolve(URI.create("http://svc/path"), null)).isEqualTo("svc:80");
    }

    @Test
    void missingPortDefaultsToFourFourThreeForHttps() {
        assertThat(resolver.resolve(URI.create("https://svc/path"), null)).isEqualTo("svc:443");
    }

    @Test
    void nullOrHostlessUriDegradesToUnknown() {
        assertThat(resolver.resolve(null, null)).isEqualTo("unknown");
        assertThat(resolver.resolve(URI.create("/relative/path"), null)).isEqualTo("unknown");
    }

    @Test
    void sourceNameIsTheConfiguredApplicationName() {
        assertThat(new DestinationNameResolver("billing").getSourceName()).isEqualTo("billing");
    }

    @Test
    void sourceNameDegradesToUnknownWhenApplicationNameIsAbsent() {
        assertThat(new DestinationNameResolver(null).getSourceName()).isEqualTo("unknown");
        assertThat(new DestinationNameResolver("").getSourceName()).isEqualTo("unknown");
        assertThat(new DestinationNameResolver("  ").getSourceName()).isEqualTo("unknown");
    }
}
