package com.bookit.servicecalllogging.metrics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResponseCodeResultTest {

    @Test
    void zeroIsSuccess() {
        ResponseCodeResult result = ResponseCodeResult.of(0);
        assertThat(result.outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(result.rawCode()).isEqualTo(0);
    }

    @Test
    void oneIsFailure() {
        ResponseCodeResult result = ResponseCodeResult.of(1);
        assertThat(result.outcome()).isEqualTo(Outcome.FAILURE);
        assertThat(result.rawCode()).isEqualTo(1);
    }

    @Test
    void anyNonZeroCodeIsFailureAndTheRawValueIsPreserved() {
        assertThat(ResponseCodeResult.of(99).outcome()).isEqualTo(Outcome.FAILURE);
        assertThat(ResponseCodeResult.of(99).rawCode()).isEqualTo(99);
        assertThat(ResponseCodeResult.of(-1).outcome()).isEqualTo(Outcome.FAILURE);
        assertThat(ResponseCodeResult.of(-1).rawCode()).isEqualTo(-1);
    }

    @Test
    void absentConstantCarriesNoRawCode() {
        assertThat(ResponseCodeResult.ABSENT.outcome()).isEqualTo(Outcome.ABSENT);
        assertThat(ResponseCodeResult.ABSENT.rawCode()).isNull();
    }
}
