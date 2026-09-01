package com.bookit.servicecalllogging.interceptor;

import com.bookit.servicecalllogging.ResponseCodeExtractor;
import com.bookit.servicecalllogging.metrics.Outcome;
import com.bookit.servicecalllogging.metrics.ResponseCodeResult;
import com.bookit.servicecalllogging.testsupport.FakeClientHttpResponse;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class BufferingClientHttpResponseTest {

    private static final int CAP = 64;

    private static final ResponseCodeExtractor JSON_RESPONSE_CODE = bytes -> {
        String text = new String(bytes, StandardCharsets.UTF_8);
        int index = text.indexOf("\"responseCode\":");
        if (index < 0) {
            return Optional.empty();
        }
        return Optional.of(Character.getNumericValue(text.charAt(index + 15)));
    };

    private static final ResponseCodeExtractor EXPLODING = bytes -> {
        throw new IllegalStateException("extractor exploded");
    };

    @Test
    void bodyWithinCapIsFullyReReadableOnEveryCall() throws Exception {
        byte[] original = "{\"responseCode\":0}".getBytes(StandardCharsets.UTF_8);
        BufferingClientHttpResponse response =
                new BufferingClientHttpResponse(new FakeClientHttpResponse(original), CAP);

        ResponseCodeResult result = response.peek(JSON_RESPONSE_CODE);

        assertThat(result.outcome()).isEqualTo(Outcome.SUCCESS);
        // re-readable: three independent reads all yield the complete original body
        assertThat(response.getBody().readAllBytes()).isEqualTo(original);
        assertThat(response.getBody().readAllBytes()).isEqualTo(original);
        assertThat(response.getBody().readAllBytes()).isEqualTo(original);
    }

    @Test
    void bodyExactlyAtCapIsTreatedAsFittingAndStaysReReadable() throws Exception {
        byte[] original = new byte[CAP];
        java.util.Arrays.fill(original, (byte) 'a');
        BufferingClientHttpResponse response =
                new BufferingClientHttpResponse(new FakeClientHttpResponse(original), CAP);

        ResponseCodeResult result = response.peek(JSON_RESPONSE_CODE);

        assertThat(result).isEqualTo(ResponseCodeResult.ABSENT);   // no responseCode in it
        assertThat(response.getBody().readAllBytes()).isEqualTo(original);
        assertThat(response.getBody().readAllBytes()).isEqualTo(original);
    }

    @Test
    void oversizedBodyStillDeliversEveryOriginalByteIncludingTheOverflowByte() throws Exception {
        byte[] original = new byte[CAP * 3 + 7];
        for (int i = 0; i < original.length; i++) {
            original[i] = (byte) (i % 251);
        }
        BufferingClientHttpResponse response =
                new BufferingClientHttpResponse(new FakeClientHttpResponse(original), CAP);

        ResponseCodeResult result = response.peek(JSON_RESPONSE_CODE);

        // FR-013: past the cap we do not parse at all
        assertThat(result).isEqualTo(ResponseCodeResult.ABSENT);
        // ...but the caller still receives the complete, unmodified body
        assertThat(response.getBody().readAllBytes()).isEqualTo(original);
    }

    @Test
    void capOfZeroSkipsParsingEntirelyButPreservesTheBody() throws Exception {
        byte[] original = "{\"responseCode\":0}".getBytes(StandardCharsets.UTF_8);
        BufferingClientHttpResponse response =
                new BufferingClientHttpResponse(new FakeClientHttpResponse(original), 0);

        assertThat(response.peek(JSON_RESPONSE_CODE)).isEqualTo(ResponseCodeResult.ABSENT);
        assertThat(response.getBody().readAllBytes()).isEqualTo(original);
    }

    @Test
    void emptyBodyDegradesToAbsentAndStaysEmpty() throws Exception {
        BufferingClientHttpResponse response =
                new BufferingClientHttpResponse(new FakeClientHttpResponse(new byte[0]), CAP);

        assertThat(response.peek(JSON_RESPONSE_CODE)).isEqualTo(ResponseCodeResult.ABSENT);
        assertThat(response.getBody().readAllBytes()).isEmpty();
    }

    @Test
    void extractorExceptionDegradesToAbsentAndLeavesTheBodyReReadable() throws Exception {
        byte[] original = "{\"responseCode\":0}".getBytes(StandardCharsets.UTF_8);
        BufferingClientHttpResponse response =
                new BufferingClientHttpResponse(new FakeClientHttpResponse(original), CAP);

        ResponseCodeResult result = response.peek(EXPLODING);

        assertThat(result).isEqualTo(ResponseCodeResult.ABSENT);
        assertThat(response.getBody().readAllBytes()).isEqualTo(original);
    }

    @Test
    void extractorReturningNullIsToleratedAsAbsent() throws Exception {
        byte[] original = "{}".getBytes(StandardCharsets.UTF_8);
        BufferingClientHttpResponse response =
                new BufferingClientHttpResponse(new FakeClientHttpResponse(original), CAP);

        assertThat(response.peek(bytes -> null)).isEqualTo(ResponseCodeResult.ABSENT);
        assertThat(response.getBody().readAllBytes()).isEqualTo(original);
    }

    @Test
    void bodyReadFailureDegradesToAbsentAndNeverThrowsFromPeek() {
        BufferingClientHttpResponse response =
                new BufferingClientHttpResponse(FakeClientHttpResponse.failingBody(), CAP);

        assertThatCode(() -> assertThat(response.peek(JSON_RESPONSE_CODE))
                .isEqualTo(ResponseCodeResult.ABSENT))
                .doesNotThrowAnyException();
    }

    @Test
    void peekIsIdempotentAndNeverConsumesTheBodyTwice() throws Exception {
        byte[] original = "{\"responseCode\":1}".getBytes(StandardCharsets.UTF_8);
        BufferingClientHttpResponse response =
                new BufferingClientHttpResponse(new FakeClientHttpResponse(original), CAP);

        ResponseCodeResult first = response.peek(JSON_RESPONSE_CODE);
        ResponseCodeResult second = response.peek(JSON_RESPONSE_CODE);

        assertThat(first.outcome()).isEqualTo(Outcome.FAILURE);
        assertThat(second).isEqualTo(first);
        assertThat(response.getBody().readAllBytes()).isEqualTo(original);
    }

    @Test
    void statusHeadersAndCloseAllDelegateUnchanged() throws Exception {
        FakeClientHttpResponse delegate =
                new FakeClientHttpResponse(org.springframework.http.HttpStatus.BAD_REQUEST, new byte[0]);
        BufferingClientHttpResponse response = new BufferingClientHttpResponse(delegate, CAP);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getStatusText()).isEqualTo(delegate.getStatusText());
        assertThat(response.getHeaders()).isSameAs(delegate.getHeaders());

        response.close();
        assertThat(delegate.isClosed()).isTrue();
    }
}
