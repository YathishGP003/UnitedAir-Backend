package com.unitedair.ai.feedback;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

import com.unitedair.ai.shared.ApiExceptions;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class FeedbackDtos {

    private FeedbackDtos() { }

    public enum Rating {
        UP, DOWN;

        public static Rating parse(String value) {
            try {
                return valueOf(value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException invalid) {
                throw new ApiExceptions.BadRequest("Feedback rating must be UP or DOWN.");
            }
        }
    }

    public enum Reason {
        INACCURATE,
        MISSING_DETAIL,
        HARD_TO_UNDERSTAND,
        WRONG_SOURCE,
        OTHER;

        public static Reason parse(String value) {
            if (value == null || value.isBlank()) return null;
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
            } catch (IllegalArgumentException invalid) {
                throw new ApiExceptions.BadRequest("Unknown feedback reason.");
            }
        }
    }

    public record Submit(
            @NotBlank @Size(max = 36) String traceId,
            @NotBlank @Size(max = 8) String rating,
            @Size(max = 32) String reason) { }

    public record Receipt(String traceId, String rating, String reason, boolean updated) { }

    public record DailyRoleStat(
            LocalDate day,
            String actorRole,
            long up,
            long down) { }

    public record Statistics(
            long total,
            long up,
            long down,
            double helpfulRatio,
            List<DailyRoleStat> byRoleAndDay) { }
}
