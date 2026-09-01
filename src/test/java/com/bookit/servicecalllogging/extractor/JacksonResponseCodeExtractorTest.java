package com.bookit.servicecalllogging.extractor;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class JacksonResponseCodeExtractorTest {

    private final JacksonResponseCodeExtractor extractor = new JacksonResponseCodeExtractor();

    private Optional<Integer> extract(String body) {
        return extractor.extract(body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void extractsZero() {
        assertThat(extract("{\"responseCode\":0,\"message\":\"OK\"}")).contains(0);
    }

    @Test
    void extractsOne() {
        assertThat(extract("{\"responseCode\":1,\"message\":\"failed\"}")).contains(1);
    }

    @Test
    void extractsAnyOtherIntegerCode() {
        assertThat(extract("{\"responseCode\":99}")).contains(99);
    }

    @Test
    void missingFieldYieldsEmpty() {
        assertThat(extract("{\"status\":\"OK\"}")).isEmpty();
    }

    @Test
    void nullFieldYieldsEmpty() {
        assertThat(extract("{\"responseCode\":null}")).isEmpty();
    }

    @Test
    void wrongTypeYieldsEmpty() {
        assertThat(extract("{\"responseCode\":\"zero\"}")).isEmpty();
        assertThat(extract("{\"responseCode\":{\"nested\":0}}")).isEmpty();
    }

    @Test
    void nonJsonBytesYieldEmpty() {
        assertThat(extract("<html><body>not json</body></html>")).isEmpty();
        assertThat(extract("plain text")).isEmpty();
    }

    @Test
    void emptyOrNullInputYieldsEmpty() {
        assertThat(extractor.extract(new byte[0])).isEmpty();
        assertThat(extractor.extract(null)).isEmpty();
    }

    @Test
    void nonObjectJsonRootYieldsEmpty() {
        assertThat(extract("[1,2,3]")).isEmpty();
        assertThat(extract("42")).isEmpty();
    }

    @Test
    void neverThrowsForAnyInput() {
        assertThatCode(() -> {
            extractor.extract(null);
            extractor.extract(new byte[0]);
            extractor.extract(new byte[]{(byte) 0xC3, (byte) 0x28});   // invalid UTF-8
            extract("{unclosed");
            extract("{\"responseCode\":");
        }).doesNotThrowAnyException();
    }
}
