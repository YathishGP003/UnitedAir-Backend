package com.unitedair.ai.feedback;

import com.unitedair.ai.identity.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FeedbackController {

    private final FeedbackRepository feedback;
    private final CurrentUser currentUser;

    public FeedbackController(FeedbackRepository feedback, CurrentUser currentUser) {
        this.feedback = feedback;
        this.currentUser = currentUser;
    }

    @PostMapping("/feedback")
    public FeedbackDtos.Receipt submit(@Valid @RequestBody FeedbackDtos.Submit submit) {
        return feedback.save(currentUser.require(), submit);
    }

    @GetMapping("/admin/feedback/statistics")
    @PreAuthorize("hasRole('ADMIN')")
    public FeedbackDtos.Statistics statistics() {
        return feedback.statistics();
    }
}
