package com.telecelghana.play.app.common.servicecalllogging.metrics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResponseCodeResultTest {

    @Test
    void zeroIsSuccess() {
        ResponseCodeResult result = ResponseCodeResult.of(0, 0);
        assertThat(result.outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(result.rawCode()).isEqualTo(0);
    }

    @Test
    void oneIsFailure() {
        ResponseCodeResult result = ResponseCodeResult.of(1, 0);
        assertThat(result.outcome()).isEqualTo(Outcome.FAILURE);
        assertThat(result.rawCode()).isEqualTo(1);
    }

    @Test
    void anyNonZeroCodeIsFailureAndTheRawValueIsPreserved() {
        assertThat(ResponseCodeResult.of(99, 0).outcome()).isEqualTo(Outcome.FAILURE);
        assertThat(ResponseCodeResult.of(99, 0).rawCode()).isEqualTo(99);
        assertThat(ResponseCodeResult.of(-1, 0).outcome()).isEqualTo(Outcome.FAILURE);
        assertThat(ResponseCodeResult.of(-1, 0).rawCode()).isEqualTo(-1);
    }

    @Test
    void absentConstantCarriesNoRawCode() {
        assertThat(ResponseCodeResult.ABSENT.outcome()).isEqualTo(Outcome.ABSENT);
        assertThat(ResponseCodeResult.ABSENT.rawCode()).isNull();
    }

    @Test
    void aConfiguredSuccessfulValueDecidesWhatCountsAsSuccess() {
        ResponseCodeResult result = ResponseCodeResult.of(1, 1);

        assertThat(result.outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(result.rawCode()).isEqualTo(1);
    }

    @Test
    void zeroIsUnsuccessfulWhenTheConfiguredSuccessfulValueIsNotZero() {
        ResponseCodeResult result = ResponseCodeResult.of(0, 200);

        assertThat(result.outcome()).isEqualTo(Outcome.FAILURE);
        assertThat(result.rawCode()).isEqualTo(0);
    }

    @Test
    void everyValueOtherThanTheSuccessfulOneIsUnsuccessfulAndKeepsItsRawValue() {
        assertThat(ResponseCodeResult.of(2, 1).outcome()).isEqualTo(Outcome.FAILURE);
        assertThat(ResponseCodeResult.of(2, 1).rawCode()).isEqualTo(2);
        // a code never previously designated as a failure is still a failure, not "absent"
        assertThat(ResponseCodeResult.of(4711, 1).outcome()).isEqualTo(Outcome.FAILURE);
        assertThat(ResponseCodeResult.of(4711, 1).rawCode()).isEqualTo(4711);
        assertThat(ResponseCodeResult.of(-7, 1).outcome()).isEqualTo(Outcome.FAILURE);
    }
}
