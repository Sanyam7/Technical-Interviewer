package com.techinterviewer.interview.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.techinterviewer.TestJson;
import com.techinterviewer.interview.api.dto.PublicPlan;
import com.techinterviewer.interview.api.dto.PublicSectionPlan;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.persistence.Fixtures;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class PublicPlanMapperTest {

    @Test
    void everySectionKeepsItsMinutesAndItsPlannedQuestionsInPlanOrder() {
        PublicPlan plan = PublicPlanMapper.toPublic(Fixtures.plan());

        assertThat(plan.sections()).containsExactly(
                new PublicSectionPlan(Section.INTRODUCTION, 4, 1),
                new PublicSectionPlan(Section.QUESTIONING, 24, 6),
                new PublicSectionPlan(Section.CODING, 16, 1),
                new PublicSectionPlan(Section.SYSTEM_DESIGN, 14, 1));
    }

    @Test
    void theJsonNeverShowsTopicQuotasDifficultyOrFollowUpDepth() {
        JsonNode tree = TestJson.applicationMapper().valueToTree(PublicPlanMapper.toPublic(Fixtures.plan()));

        assertThat(tree.propertyNames()).containsExactly("sections");
        for (JsonNode section : tree.get("sections")) {
            assertThat(section.propertyNames()).containsExactlyInAnyOrder("section", "minutes", "plannedQuestions");
        }
        assertThat(tree.toString()).doesNotContain("java/concurrency", "topicQuotas", "difficulty", "maxFollowUpDepth", "slots");
        assertThat(tree.get("sections")).hasSize(4);
    }
}
