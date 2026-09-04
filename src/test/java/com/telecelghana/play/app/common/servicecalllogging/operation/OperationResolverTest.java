package com.telecelghana.play.app.common.servicecalllogging.operation;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Spec 003, T027 — the business operation dimension.
 *
 * <p>Two bounds apply together, and they protect different things. The <em>shape</em> bound
 * (FR-020) keeps a single value from being a smuggled identifier or a free-text payload. The
 * <em>distinct-value cap</em> (FR-021) keeps the number of values bounded, which the shape bound
 * alone cannot do — {@code SendMoney-0001}, {@code SendMoney-0002} … all satisfy the shape while
 * being unbounded in number. That gap was the contradiction the clarification session closed.
 */
class OperationResolverTest {

    private final OperationResolver resolver = new OperationResolver();

    @Test
    void theFixedHeaderNameIsExactlyXOperation() {
        // FR-018 — a per-call caller input, never a configurable per-service identity.
        assertThat(OperationResolver.HEADER_NAME).isEqualTo("X-Operation");
    }

    @Test
    void aSuppliedValueIsReportedAsGiven() {
        assertThat(this.resolver.resolve("SendMoney")).isEqualTo("SendMoney");
    }

    @Test
    void anAbsentHeaderIsUndefined() {
        assertThat(this.resolver.resolve(null)).isEqualTo(OperationResolver.UNDEFINED);
    }

    @Test
    void anEmptyOrWhitespaceOnlyValueIsUndefined() {
        assertThat(this.resolver.resolve("")).isEqualTo(OperationResolver.UNDEFINED);
        assertThat(this.resolver.resolve("   ")).isEqualTo(OperationResolver.UNDEFINED);
        assertThat(this.resolver.resolve("\t")).isEqualTo(OperationResolver.UNDEFINED);
    }

    // ===== FR-020: the shape bound =====

    @Test
    void aValueOfExactlyTheMaximumLengthIsAccepted() {
        String atLimit = "a".repeat(OperationResolver.MAX_LENGTH);

        assertThat(this.resolver.resolve(atLimit)).isEqualTo(atLimit);
    }

    @Test
    void aValueOneCharacterOverTheMaximumLengthIsUndefined() {
        String overLimit = "a".repeat(OperationResolver.MAX_LENGTH + 1);

        assertThat(this.resolver.resolve(overLimit)).isEqualTo(OperationResolver.UNDEFINED);
    }

    @Test
    void everyPermittedCharacterClassIsAccepted() {
        assertThat(this.resolver.resolve("Send.Money_v2-BETA99"))
                .isEqualTo("Send.Money_v2-BETA99");
    }

    @Test
    void charactersOutsideThePermittedSetAreUndefined() {
        // Separators and whitespace would break log-line parsing; the rest keep the value from
        // becoming a vehicle for arbitrary caller-supplied text.
        for (String hostile : new String[]{
                "Send Money", "Send/Money", "Send:Money", "Send=Money", "Send,Money",
                "Send\tMoney", "Send\nMoney", "Send\"Money", "acct#12345", "Sénd", "送金",
                "Send{Money}", "op;drop", "a b"}) {
            assertThat(this.resolver.resolve(hostile))
                    .as("%s must not be accepted as an operation", hostile)
                    .isEqualTo(OperationResolver.UNDEFINED);
        }
    }

    // ===== FR-021: the distinct-value cap =====

    @Test
    void distinctValuesAreAdmittedUpToTheDocumentedCap() {
        for (int i = 0; i < OperationResolver.MAX_DISTINCT_VALUES; i++) {
            String value = "Operation" + i;
            assertThat(this.resolver.resolve(value))
                    .as("value %d must still be admitted", i)
                    .isEqualTo(value);
        }
    }

    @Test
    void theFirstValueBeyondTheCapIsUndefined() {
        for (int i = 0; i < OperationResolver.MAX_DISTINCT_VALUES; i++) {
            this.resolver.resolve("Operation" + i);
        }

        assertThat(this.resolver.resolve("OneTooMany"))
                .as("the %dth distinct value must report as undefined",
                        OperationResolver.MAX_DISTINCT_VALUES + 1)
                .isEqualTo(OperationResolver.UNDEFINED);
    }

    @Test
    void anAlreadyAdmittedValueKeepsWorkingAfterTheCapIsReached() {
        for (int i = 0; i < OperationResolver.MAX_DISTINCT_VALUES; i++) {
            this.resolver.resolve("Operation" + i);
        }
        this.resolver.resolve("Rejected");

        assertThat(this.resolver.resolve("Operation0"))
                .as("occupancy is first-come-first-served, not evicted by later traffic")
                .isEqualTo("Operation0");
    }

    @Test
    void aHighVolumeJunkCallerNeverEvictsAnEstablishedOperation() {
        // FR-021 forbids ranking or eviction: a dimension whose membership shifts under load
        // would change a dashboard's meaning mid-incident.
        this.resolver.resolve("SendMoney");
        for (int i = 0; i < 5_000; i++) {
            this.resolver.resolve("Junk" + i);
        }

        assertThat(this.resolver.resolve("SendMoney")).isEqualTo("SendMoney");
    }

    @Test
    void theNumberOfDistinctAdmittedValuesStaysWithinTheCap() {
        Set<String> reported = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            reported.add(this.resolver.resolve("Operation" + i));
        }

        assertThat(reported)
                .as("at most the cap plus the single undefined literal")
                .hasSizeLessThanOrEqualTo(OperationResolver.MAX_DISTINCT_VALUES + 1);
        assertThat(reported).contains(OperationResolver.UNDEFINED);
    }

    @Test
    void theDocumentedBoundsAreTheValuesTheReadmeStates() {
        assertThat(OperationResolver.MAX_LENGTH).isEqualTo(64);
        assertThat(OperationResolver.MAX_DISTINCT_VALUES).isEqualTo(100);
        assertThat(OperationResolver.UNDEFINED).isEqualTo("undefined");
    }

    @Test
    void resolvingNeverThrowsWhateverTheInput() {
        // FR-037 — no step of this instrumentation may throw into the business call path.
        for (String input : new String[]{null, "", " ", "\0", "￿", "a".repeat(100_000)}) {
            assertThatCode(() -> this.resolver.resolve(input)).doesNotThrowAnyException();
        }
    }

    @Test
    void theCapIsPerResolverInstanceSoOneServiceCannotExhaustAnother() {
        for (int i = 0; i < OperationResolver.MAX_DISTINCT_VALUES; i++) {
            this.resolver.resolve("Operation" + i);
        }

        assertThat(new OperationResolver().resolve("Fresh")).isEqualTo("Fresh");
    }
}
