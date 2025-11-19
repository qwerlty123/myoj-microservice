package com.qwerlty.myojbackendaiservice.knowledge;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeInitializationConfigurationTest {

    @Test
    void documentedEnvironmentVariablesEnableSchemaAndVersionedCollectionInitialization() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                "documentedKnowledgeEnvironment",
                Map.of(
                        "AI_KNOWLEDGE_INITIALIZE", "true",
                        "AI_KNOWLEDGE_VERSION", "v2"
                )
        ));
        List<PropertySource<?>> yamlSources = new YamlPropertySourceLoader()
                .load("applicationConfig", new ClassPathResource("application.yml"));
        yamlSources.forEach(environment.getPropertySources()::addLast);

        assertThat(environment.getProperty("myoj.ai.knowledge.initialize", Boolean.class)).isTrue();
        assertThat(environment.getProperty("myoj.ai.knowledge-version")).isEqualTo("v2");
        assertThat(environment.getProperty("spring.ai.vectorstore.qdrant.initialize-schema", Boolean.class)).isTrue();
        assertThat(environment.getProperty("spring.ai.vectorstore.qdrant.collection-name"))
                .isEqualTo("myoj_knowledge_v2");
    }
}
