package com.qwerlty.myojbackendaiservice.generation.knowledge;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded read-only retrieval over the versioned MyOJ knowledge corpus. */
@Component
public class AuthoringKnowledgeRetriever {
    private final VectorStore vectorStore;
    private final int topK;
    private final double similarityThreshold;
    private final int maxContentChars;

    public AuthoringKnowledgeRetriever(
            VectorStore vectorStore,
            @Value("${myoj.ai.generation.knowledge-tool.top-k:3}") int topK,
            @Value("${myoj.ai.generation.knowledge-tool.similarity-threshold:0.68}") double similarityThreshold,
            @Value("${myoj.ai.generation.knowledge-tool.max-content-chars:1200}") int maxContentChars) {
        this.vectorStore = vectorStore;
        this.topK = Math.max(1, Math.min(5, topK));
        this.similarityThreshold = Math.max(0.0, Math.min(1.0, similarityThreshold));
        this.maxContentChars = Math.max(300, Math.min(2_000, maxContentChars));
    }

    public List<AuthoringKnowledgeResult.KnowledgeHit> search(String query) {
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(similarityThreshold)
                .filterExpression("audience == 'authoring'")
                .build();
        Map<String, AuthoringKnowledgeResult.KnowledgeHit> unique = new LinkedHashMap<>();
        for (Document document : vectorStore.similaritySearch(request)) {
            String docId = metadata(document, "docId", document.getId());
            String title = metadata(document, "title", docId);
            unique.putIfAbsent(docId, new AuthoringKnowledgeResult.KnowledgeHit(
                    docId, title, truncate(document.getText())));
        }
        return unique.values().stream().limit(topK).toList();
    }

    private String metadata(Document document, String key, String fallback) {
        Object value = document.getMetadata().get(key);
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }

    private String truncate(String value) {
        if (value == null) return "";
        if (value.length() <= maxContentChars) return value;
        return value.substring(0, maxContentChars) + "…";
    }
}
