package com.telecelghana.play.app.common.servicecalllogging.metrics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OutcomeTest {

    @Test
    void labelsMatchTheDocumentedMetricTagValues() {
        assertThat(Outcome.SUCCESS.label()).isEqualTo("success");
        assertThat(Outcome.FAILURE.label()).isEqualTo("failure");
        assertThat(Outcome.ABSENT.label()).isEqualTo("absent");
    }

    @Test
    void exposesExactlyThreeValues() {
        assertThat(Outcome.values()).containsExactly(Outcome.SUCCESS, Outcome.FAILURE, Outcome.ABSENT);
    }
}
