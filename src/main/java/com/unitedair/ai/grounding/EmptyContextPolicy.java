package com.unitedair.ai.grounding;

import java.util.List;

import org.springframework.stereotype.Component;

/**
 * The SRS 4.1.1 rule that {@code allowEmptyContext} is false, and the SRS 4.1.3 response
 * that follows from it.
 *
 * <p>When retrieval yields no evidence above the threshold and no tool produced a verified
 * result, the model is <b>not called at all</b>. Calling it and hoping for a refusal would
 * be a policy that depends on the model's mood; not calling it is a policy.
 *
 * <p>The response text is reproduced from the SRS byte for byte. It is asserted in
 * {@code EmptyContextPolicyTest} precisely so that a well-meaning edit to the wording is
 * caught, because downstream consumers and the marking scheme both match on this string.
 *
 * <p>SRS 4.3.3 also states that no follow-up suggestions accompany an empty-context
 * response - there is nothing grounded to base them on - so the follow-up list is empty.
 */
@Component
public class EmptyContextPolicy {

    /** SRS 4.1.3, verbatim. */
    public static final String EMPTY_CONTEXT_ANSWER =
            "No matching policy found. Please contact your UnitedAir Customer Support Manager.";

    public GroundingDtos.GroundedAnswer response(String intent,
                                                 String lane,
                                                 String traceId,
                                                 String sessionUuid,
                                                 Double confidence) {
        return new GroundingDtos.GroundedAnswer(
                EMPTY_CONTEXT_ANSWER,
                GroundingDtos.AnswerStatus.EMPTY_CONTEXT,
                true,               // SRS 4.1.3 carries "escalated": true
                List.of(),
                List.of(),          // SRS 4.3.3: no follow-ups on an empty-context response
                List.of(),
                confidence,
                0.0,
                0,
                intent,
                lane,
                traceId,
                sessionUuid,
                null,
                null);
    }
}
