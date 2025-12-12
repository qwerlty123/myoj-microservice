package com.qwerlty.myojbackendaiservice.generation.skill;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class AuthoringSkillCorpusTest {

    @Test
    void everySkillHasRuntimeMetadataAndRetrievableKnowledge() throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] skills = resolver.getResources("classpath*:authoring-skills/*/SKILL.md");
        Resource[] references = resolver.getResources("classpath*:authoring-skills/*/references/**/*.md");

        assertThat(skills).hasSize(5);
        assertThat(references).hasSize(5);
        for (Resource skill : skills) {
            assertThat(skill.createRelative("authoring.properties").exists())
                    .as(skill.getDescription()).isTrue();
            String text = StreamUtils.copyToString(skill.getInputStream(), StandardCharsets.UTF_8);
            assertThat(text).contains("name:", "description:", "## DRAFT_SPECIFICATION",
                    "## TEST_CASE_GENERATION", "## QUALITY_REVIEW");
        }
        for (Resource reference : references) {
            String text = StreamUtils.copyToString(reference.getInputStream(), StandardCharsets.UTF_8);
            assertThat(text)
                    .contains("docId:", "title:", "topic:", "language:", "errorType:",
                            "source: MyOJ Authoring Skills", "audience: authoring")
                    .doesNotContain("judgeCase:", "hiddenInput:", "hiddenOutput:", "answer:");
        }
    }
}
