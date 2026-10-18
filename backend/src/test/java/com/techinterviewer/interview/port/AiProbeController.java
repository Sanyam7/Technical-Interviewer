package com.techinterviewer.interview.port;

import com.techinterviewer.interview.domain.ExperienceLevel;
import com.techinterviewer.interview.domain.QuestionKind;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.domain.TargetRole;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Test-only endpoint that calls a port, to see what a client gets when no AI layer exists. */
@RestController
@Profile(AiProbeController.PROFILE)
@RequestMapping("/test/ai")
class AiProbeController {

    static final String PROFILE = "ai-port-test";

    private static final QuestionRequest REQUEST = new QuestionRequest(UUID.randomUUID(), Section.QUESTIONING, QuestionKind.TECHNICAL,
            new TopicRef("java", "Java", "concurrency", "Concurrency"), 3, TargetRole.BACKEND, ExperienceLevel.MID,
            null, null, null, null, null);

    private final QuestionGenerator generator;

    AiProbeController(QuestionGenerator generator) {
        this.generator = generator;
    }

    @GetMapping("/question")
    String question() {
        return generator.generate(REQUEST).prompt();
    }
}
