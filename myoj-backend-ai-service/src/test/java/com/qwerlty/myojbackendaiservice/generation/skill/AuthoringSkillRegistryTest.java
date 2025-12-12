package com.qwerlty.myojbackendaiservice.generation.skill;

import com.qwerlty.myojbackendaiservice.model.dto.generation.GeneratedProblemSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AuthoringSkillRegistryTest {

    private final AuthoringSkillRegistry registry = new AuthoringSkillRegistry(3);

    @Test
    void selectsTheUniversalSkillAndOnlyRelevantDomainSkills() {
        GeneratedProblemSpec graph = new GeneratedProblemSpec();
        graph.setTitle("无向图最短路");
        graph.setTags(List.of("graph", "最短路"));

        AuthoringSkillSelection selection = registry.select(
                AuthoringSkillContext.from(AuthoringSkillPhase.COVERAGE_PLAN, graph));

        assertThat(selection.identifiers()).containsExactly(
                "enforce-problem-contract@1.0.0", "cover-graph-structure@1.0.0");
        assertThat(selection.guidance())
                .contains("isolated vertex", "direction reversal")
                .doesNotContain("State direction, weight domain", "Derive traversal state");
    }

    @Test
    void capsSelectionAndDoesNotMatchShortAsciiKeywordsInsideWords() {
        AuthoringSkillSelection unrelated = registry.select(new AuthoringSkillContext(
                AuthoringSkillPhase.QUALITY_REVIEW, "adaptation and ordinary prose"));
        assertThat(unrelated.identifiers()).containsExactly("enforce-problem-contract@1.0.0");

        AuthoringSkillSelection crowded = registry.select(new AuthoringSkillContext(
                AuthoringSkillPhase.TEST_CASE_GENERATION,
                "array 前缀和 math 动态规划 dp 背包 graph 最短路"));
        assertThat(crowded.identifiers())
                .hasSize(3)
                .startsWith("enforce-problem-contract@1.0.0");
    }

    @Test
    void catalogFingerprintIsStableAndIncludedInGuidance() {
        AuthoringSkillSelection selection = registry.select(new AuthoringSkillContext(
                AuthoringSkillPhase.DRAFT_SPECIFICATION, "字符串"));

        assertThat(registry.catalogFingerprint()).hasSize(12);
        assertThat(selection.guidance()).contains("catalog=\"" + registry.catalogFingerprint() + "\"");
    }
}
