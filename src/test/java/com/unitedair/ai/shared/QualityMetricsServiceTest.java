package com.unitedair.ai.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class QualityMetricsServiceTest {

    @Test
    void distinguishesHostedCompletionsFromRateLimitAndToolFallbacks() {
        var rows = List.of(
                new QualityMetricsService.QualityRow(
                        "LIVE", null, "GROUNDED", 0.9, 1.0, 1000L, 0),
                new QualityMetricsService.QualityRow(
                        "LIVE", "RATE_LIMIT", "GROUNDED", 0.7, 0.8, 2000L, 1),
                new QualityMetricsService.QualityRow(
                        "LIVE", null, "TOOL_GROUNDED", 1.0, 1.0, 500L, 0));

        var summary = QualityMetricsService.summarize(rows, 2);

        assertThat(summary.windowAnswers()).isEqualTo(3);
        assertThat(summary.hostedCompletions()).isEqualTo(1);
        assertThat(summary.rateLimitFallbacks()).isEqualTo(1);
        assertThat(summary.toolOnlyAnswers()).isEqualTo(1);
        assertThat(summary.fallbackRate()).isEqualTo(1.0 / 3.0);
        assertThat(summary.validationRepairs()).isEqualTo(1);
        assertThat(summary.escalations()).isEqualTo(2);
    }
}
