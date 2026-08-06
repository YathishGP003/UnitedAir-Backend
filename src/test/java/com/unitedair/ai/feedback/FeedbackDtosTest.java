package com.unitedair.ai.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class FeedbackDtosTest {

    @Test
    void acceptsOnlyBoundedFeedbackEnums() {
        assertThat(FeedbackDtos.Rating.parse("up")).isEqualTo(FeedbackDtos.Rating.UP);
        assertThat(FeedbackDtos.Reason.parse("missing_detail"))
                .isEqualTo(FeedbackDtos.Reason.MISSING_DETAIL);
        assertThatThrownBy(() -> FeedbackDtos.Rating.parse("excellent"))
                .hasMessageContaining("UP or DOWN");
    }
}
