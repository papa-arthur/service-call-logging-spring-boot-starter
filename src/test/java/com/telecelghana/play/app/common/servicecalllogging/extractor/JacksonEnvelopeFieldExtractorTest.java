package com.telecelghana.play.app.common.servicecalllogging.extractor;

import com.telecelghana.play.app.common.servicecalllogging.EnvelopeMatch;
import com.telecelghana.play.app.common.servicecalllogging.ServiceCallLoggingProperties.Envelope;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The matching contract in {@code contracts/envelope-matching.md}: an ordered list of
 * combinations, first code-field match wins, built-in default appended last, message read
 * independently of the code.
 */
class JacksonEnvelopeFieldExtractorTest {

    private static EnvelopeMatch match(List<Envelope> envelopes, String body) {
        return new JacksonEnvelopeFieldExtractor(envelopes)
                .extract(body == null ? null : body.getBytes(StandardCharsets.UTF_8));
    }

    private static Envelope envelope(String codeField, String messageField, int successfulValue) {
        return new Envelope(codeField, messageField, successfulValue);
    }

    // ---------- built-in default (nothing configured) ----------

    @Test
    void withNoConfigurationTheBuiltInDefaultCombinationApplies() {
        EnvelopeMatch result = match(List.of(), "{\"responseCode\":0,\"message\":\"OK\"}");

        assertThat(result.rawCode()).isEqualTo(0);
        assertThat(result.successfulValue()).isEqualTo(0);
        assertThat(result.message()).isEqualTo("OK");
    }

    @Test
    void aBodyMatchingNothingYieldsNoCodeAndNoMessage() {
        EnvelopeMatch result = match(List.of(envelope("statusCode", "message", 0)),
                "{\"errorCode\":500,\"detail\":\"boom\"}");

        assertThat(result.rawCode()).isNull();
        assertThat(result.message()).isNull();
        assertThat(result.successfulValue()).isEqualTo(0);
    }

    // ---------- configured field names ----------

    @Test
    void aConfiguredCombinationIsUsedInPlaceOfTheDefaultFieldNames() {
        EnvelopeMatch result = match(List.of(envelope("statusCode", "message", 0)),
                "{\"statusCode\":7,\"message\":\"Declined\"}");

        assertThat(result.rawCode()).isEqualTo(7);
        assertThat(result.message()).isEqualTo("Declined");
    }

    @Test
    void aCombinationCarriesItsOwnSuccessfulValue() {
        EnvelopeMatch result = match(List.of(envelope("responseCode", "responseDescription", 1)),
                "{\"responseCode\":1,\"responseDescription\":\"OK\"}");

        assertThat(result.rawCode()).isEqualTo(1);
        assertThat(result.successfulValue()).isEqualTo(1);
        assertThat(result.message()).isEqualTo("OK");
    }

    // ---------- ordering ----------

    @Test
    void theFirstCombinationWhoseCodeFieldIsPresentWins() {
        List<Envelope> envelopes = List.of(
                envelope("statusCode", "message", 0),
                envelope("responseCode", "responseDescription", 1));

        EnvelopeMatch result = match(envelopes,
                "{\"statusCode\":0,\"responseCode\":1,\"message\":\"OK\",\"responseDescription\":\"other\"}");

        assertThat(result.rawCode()).isEqualTo(0);
        assertThat(result.successfulValue()).isEqualTo(0);
        assertThat(result.message()).isEqualTo("OK");
    }

    @Test
    void reversingTheConfiguredOrderReversesWhichCombinationWins() {
        List<Envelope> envelopes = List.of(
                envelope("responseCode", "responseDescription", 1),
                envelope("statusCode", "message", 0));

        EnvelopeMatch result = match(envelopes,
                "{\"statusCode\":0,\"responseCode\":1,\"message\":\"OK\",\"responseDescription\":\"other\"}");

        assertThat(result.rawCode()).isEqualTo(1);
        assertThat(result.successfulValue()).isEqualTo(1);
        assertThat(result.message()).isEqualTo("other");
    }

    @Test
    void anEarlierCombinationThatDoesNotMatchIsSkippedForALaterOneThatDoes() {
        List<Envelope> envelopes = List.of(
                envelope("statusCode", "message", 0),
                envelope("responseCode", "responseDescription", 1));

        EnvelopeMatch result = match(envelopes, "{\"responseCode\":9,\"responseDescription\":\"nope\"}");

        assertThat(result.rawCode()).isEqualTo(9);
        assertThat(result.successfulValue()).isEqualTo(1);
        assertThat(result.message()).isEqualTo("nope");
    }

    @Test
    void theBuiltInDefaultIsTriedAfterEveryConfiguredCombination() {
        EnvelopeMatch result = match(List.of(envelope("statusCode", "message", 5)),
                "{\"responseCode\":0,\"message\":\"OK\"}");

        assertThat(result.rawCode()).isEqualTo(0);
        assertThat(result.successfulValue()).isEqualTo(0);
        assertThat(result.message()).isEqualTo("OK");
    }

    // ---------- code / message independence ----------

    @Test
    void aMissingMessageFieldDoesNotStopACombinationFromMatching() {
        EnvelopeMatch result = match(List.of(envelope("statusCode", "message", 0)),
                "{\"statusCode\":3}");

        assertThat(result.rawCode()).isEqualTo(3);
        assertThat(result.message()).isNull();
    }

    @Test
    void aNonStringMessageIsTreatedAsAbsentWhileTheCodeStillCounts() {
        assertThat(match(List.of(), "{\"responseCode\":0,\"message\":42}").message()).isNull();
        assertThat(match(List.of(), "{\"responseCode\":0,\"message\":{\"a\":1}}").message()).isNull();
        assertThat(match(List.of(), "{\"responseCode\":0,\"message\":42}").rawCode()).isEqualTo(0);
    }

    @Test
    void aNonIntegerCodeDoesNotMatchThatCombination() {
        assertThat(match(List.of(), "{\"responseCode\":\"zero\"}").rawCode()).isNull();
        assertThat(match(List.of(), "{\"responseCode\":null}").rawCode()).isNull();
        assertThat(match(List.of(), "{\"responseCode\":{\"nested\":0}}").rawCode()).isNull();
    }

    // ---------- per-field fallback (FR-006) ----------

    @Test
    void anEntryWithABlankMessageFieldFallsBackToTheDefaultMessageFieldName() {
        EnvelopeMatch result = match(List.of(envelope("statusCode", "  ", 0)),
                "{\"statusCode\":0,\"message\":\"OK\"}");

        assertThat(result.rawCode()).isEqualTo(0);
        assertThat(result.message()).isEqualTo("OK");
    }

    @Test
    void anEntryWithABlankCodeFieldFallsBackToTheDefaultCodeFieldName() {
        EnvelopeMatch result = match(List.of(envelope(null, "detail", 0)),
                "{\"responseCode\":4,\"detail\":\"nope\"}");

        assertThat(result.rawCode()).isEqualTo(4);
        assertThat(result.message()).isEqualTo("nope");
    }

    // ---------- never throws (Constitution Principle I) ----------

    @Test
    void neverThrowsForAnyInput() {
        List<Envelope> withNullEntry = Arrays.asList(envelope("statusCode", "message", 0), null);

        assertThatCode(() -> {
            match(List.of(), null);
            match(null, "{\"responseCode\":0}");
            match(List.of(), "");
            match(List.of(), "not json at all");
            match(List.of(), "{unclosed");
            match(List.of(), "[1,2,3]");
            match(List.of(), "42");
            match(withNullEntry, "{\"statusCode\":0}");
            new JacksonEnvelopeFieldExtractor(List.of()).extract(new byte[]{(byte) 0xC3, (byte) 0x28});
        }).doesNotThrowAnyException();
    }

    @Test
    void malformedInputsAllDegradeToNoMatchRatherThanAnError() {
        assertThat(match(List.of(), null)).isEqualTo(EnvelopeMatch.NONE);
        assertThat(match(List.of(), "")).isEqualTo(EnvelopeMatch.NONE);
        assertThat(match(List.of(), "not json").rawCode()).isNull();
        assertThat(match(List.of(), "[1,2,3]").rawCode()).isNull();
    }
}
