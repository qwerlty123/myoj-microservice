package com.qwerlty.myojbackendaiservice.generation.knowledge;

import com.qwerlty.myojbackendaiservice.generation.GenerationValidationException;
import com.qwerlty.myojbackendaiservice.generation.workflow.WorkflowContext;
import com.qwerlty.myojbackendaiservice.model.enums.AuthoringTaskType;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthoringKnowledgeToolTest {

    @Test
    void returnsBoundedKnowledgeAndRecordsTheCall() {
        VectorStore store = new StubVectorStore(List.of(
                new Document("1", "graph guidance", Map.of("docId", "graph", "title", "图结构")),
                new Document("2", "numeric guidance", Map.of("docId", "numeric", "title", "数值安全"))));
        AuthoringKnowledgeRetriever retriever = new AuthoringKnowledgeRetriever(store, 3, 0.68, 1200);
        WorkflowContext context = WorkflowContext.testing(1L, AuthoringTaskType.TEST_CASES);
        AuthoringKnowledgeTool tool = new AuthoringKnowledgeTool(context, retriever);

        AuthoringKnowledgeResult result = tool.searchAuthoringKnowledge("最短路需要覆盖哪些结构风险");

        assertThat(result.available()).isTrue();
        assertThat(result.hits()).extracting(AuthoringKnowledgeResult.KnowledgeHit::docId)
                .containsExactly("graph", "numeric");
        assertThat(context.toolTrace()).hasSize(1);
        assertThat(context.toolTrace().get(0).toolName()).isEqualTo("searchAuthoringKnowledge");
        assertThat(context.toolTrace().get(0).outcome()).isEqualTo("KNOWLEDGE_RETURNED");
    }

    @Test
    void degradesWhenTheVectorStoreIsUnavailableAndEnforcesTheCallBudget() {
        VectorStore unavailable = new StubVectorStore(List.of()) {
            @Override public List<Document> similaritySearch(SearchRequest request) {
                throw new IllegalStateException("qdrant unavailable");
            }
        };
        AuthoringKnowledgeTool tool = new AuthoringKnowledgeTool(
                WorkflowContext.testing(2L, AuthoringTaskType.QUALITY_REVIEW),
                new AuthoringKnowledgeRetriever(unavailable, 3, 0.68, 1200));

        assertThat(tool.searchAuthoringKnowledge("检查整数溢出").available()).isFalse();
        assertThat(tool.searchAuthoringKnowledge("检查浮点精度").available()).isFalse();
        assertThatThrownBy(() -> tool.searchAuthoringKnowledge("第三次检索"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("2 次");
    }

    @Test
    void draftWorkflowCannotUseTheKnowledgeTool() {
        AuthoringKnowledgeTool tool = new AuthoringKnowledgeTool(
                WorkflowContext.testing(3L, AuthoringTaskType.PROBLEM_DRAFT),
                new AuthoringKnowledgeRetriever(new StubVectorStore(List.of()), 3, 0.68, 1200));

        assertThatThrownBy(() -> tool.searchAuthoringKnowledge("数组边界风险"))
                .isInstanceOf(GenerationValidationException.class)
                .hasMessageContaining("无权调用工具");
    }

    private static class StubVectorStore implements VectorStore {
        private final List<Document> results;

        private StubVectorStore(List<Document> results) {
            this.results = results;
        }

        @Override public void add(List<Document> documents) { }
        @Override public void delete(List<String> ids) { }
        @Override public void delete(Filter.Expression filterExpression) { }
        @Override public List<Document> similaritySearch(SearchRequest request) { return results; }
    }
}
