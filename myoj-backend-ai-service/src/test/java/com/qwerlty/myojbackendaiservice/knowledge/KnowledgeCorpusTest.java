package com.qwerlty.myojbackendaiservice.knowledge;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeCorpusTest {

    @Test
    void corpusHasExpectedSizeMetadataAndNoSensitiveFields() throws Exception {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:knowledge/**/*.md");

        assertThat(resources).hasSizeBetween(30, 50);
        for (Resource resource : resources) {
            String text = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
            assertThat(text)
                    .as(resource.getFilename())
                    .contains("docId:", "title:", "language:", "errorType:", "source:")
                    .doesNotContain("judgeCase:", "hiddenInput:", "hiddenOutput:", "answer:");
        }
    }
}
