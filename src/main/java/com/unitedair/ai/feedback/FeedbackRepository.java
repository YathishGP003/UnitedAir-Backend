package com.unitedair.ai.feedback;

import java.sql.Date;
import java.util.List;

import com.unitedair.ai.identity.CurrentUser;
import com.unitedair.ai.shared.ApiExceptions;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class FeedbackRepository {

    private final JdbcClient jdbc;

    public FeedbackRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public FeedbackDtos.Receipt save(CurrentUser.Authenticated user, FeedbackDtos.Submit submit) {
        FeedbackDtos.Rating rating = FeedbackDtos.Rating.parse(submit.rating());
        FeedbackDtos.Reason reason = FeedbackDtos.Reason.parse(submit.reason());

        OwnedAnswer answer = jdbc.sql("""
                    SELECT ar.id, ar.actor_role
                    FROM answer_record ar
                    JOIN chat_session s ON s.session_uuid = ar.session_uuid
                    WHERE ar.trace_id = :trace AND s.user_id = :user
                    ORDER BY ar.id DESC
                    LIMIT 1
                """)
                .param("trace", submit.traceId())
                .param("user", user.id())
                .query((rs, n) -> new OwnedAnswer(
                        rs.getLong("id"), rs.getString("actor_role")))
                .optional()
                .orElseThrow(() -> new ApiExceptions.Forbidden(
                        "You can only rate answers from your own conversations."));

        boolean existed = jdbc.sql("""
                    SELECT COUNT(*) FROM answer_feedback
                    WHERE trace_id = :trace AND user_id = :user
                """)
                .param("trace", submit.traceId())
                .param("user", user.id())
                .query(Long.class).single() > 0;

        jdbc.sql("""
                    INSERT INTO answer_feedback
                        (answer_record_id, trace_id, user_id, actor_role, rating, reason)
                    VALUES (:answer, :trace, :user, :role, :rating, :reason)
                    ON DUPLICATE KEY UPDATE
                        rating = VALUES(rating), reason = VALUES(reason),
                        answer_record_id = VALUES(answer_record_id)
                """)
                .param("answer", answer.id())
                .param("trace", submit.traceId())
                .param("user", user.id())
                .param("role", answer.actorRole())
                .param("rating", rating.name())
                .param("reason", reason == null ? null : reason.name())
                .update();

        return new FeedbackDtos.Receipt(
                submit.traceId(), rating.name(), reason == null ? null : reason.name(), existed);
    }

    public FeedbackDtos.Statistics statistics() {
        Totals totals = jdbc.sql("""
                    SELECT COUNT(*) total_count,
                           SUM(rating = 'UP') up_count,
                           SUM(rating = 'DOWN') down_count
                    FROM answer_feedback
                """)
                .query((rs, n) -> new Totals(
                        rs.getLong("total_count"),
                        rs.getLong("up_count"),
                        rs.getLong("down_count")))
                .single();

        List<FeedbackDtos.DailyRoleStat> daily = jdbc.sql("""
                    SELECT DATE(created_at) day, actor_role,
                           SUM(rating = 'UP') up_count,
                           SUM(rating = 'DOWN') down_count
                    FROM answer_feedback
                    GROUP BY DATE(created_at), actor_role
                    ORDER BY day DESC, actor_role
                    LIMIT 120
                """)
                .query((rs, n) -> new FeedbackDtos.DailyRoleStat(
                        ((Date) rs.getObject("day")).toLocalDate(),
                        rs.getString("actor_role"),
                        rs.getLong("up_count"),
                        rs.getLong("down_count")))
                .list();

        double ratio = totals.total() == 0 ? 0.0 : (double) totals.up() / totals.total();
        return new FeedbackDtos.Statistics(
                totals.total(), totals.up(), totals.down(), ratio, daily);
    }

    private record OwnedAnswer(long id, String actorRole) { }
    private record Totals(long total, long up, long down) { }
}
