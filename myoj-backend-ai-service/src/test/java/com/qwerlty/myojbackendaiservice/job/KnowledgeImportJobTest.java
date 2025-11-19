package com.qwerlty.myojbackendaiservice.job;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class KnowledgeImportJobTest {

    @Test
    void generatedChunkIdsAreStableQdrantCompatibleUuids() throws Exception {
        CapturingVectorStore vectorStore = new CapturingVectorStore();
        KnowledgeImportJob job = new KnowledgeImportJob(vectorStore, "v1", 400, 20);

        job.run(new DefaultApplicationArguments(new String[0]));
        List<String> firstRunIds = List.copyOf(vectorStore.deletedIds);
        job.run(new DefaultApplicationArguments(new String[0]));
        List<String> secondRunIds = List.copyOf(vectorStore.deletedIds);

        assertThat(firstRunIds).isNotEmpty().doesNotHaveDuplicates();
        assertThat(firstRunIds).isEqualTo(secondRunIds);
        firstRunIds.forEach(id -> assertThatCode(() -> UUID.fromString(id)).doesNotThrowAnyException());
    }

    @Test
    void importBatchesEmbeddingRequestsAtProviderLimit() {
        CapturingVectorStore vectorStore = new CapturingVectorStore(10);
        KnowledgeImportJob job = new KnowledgeImportJob(vectorStore, "v1", 400, 100);

        assertThatCode(() -> job.run(new DefaultApplicationArguments(new String[0])))
                .doesNotThrowAnyException();
        assertThat(vectorStore.addBatchSizes)
                .isNotEmpty()
                .allSatisfy(size -> assertThat(size).isLessThanOrEqualTo(10));
        assertThat(vectorStore.addBatchSizes).hasSizeGreaterThan(1);
    }

    private static final class CapturingVectorStore implements VectorStore {
        private final int maxAddBatchSize;
        private List<String> deletedIds = new ArrayList<>();
        private final List<Integer> addBatchSizes = new ArrayList<>();

        private CapturingVectorStore() {
            this(Integer.MAX_VALUE);
        }

        private CapturingVectorStore(int maxAddBatchSize) {
            this.maxAddBatchSize = maxAddBatchSize;
        }

        @Override
        public void add(List<Document> documents) {
            if (documents.size() > maxAddBatchSize) {
                throw new IllegalArgumentException("batch size is invalid, it should not be larger than " + maxAddBatchSize);
            }
            addBatchSizes.add(documents.size());
        }

        @Override
        public void delete(List<String> ids) {
            deletedIds = List.copyOf(ids);
        }

        @Override
        public void delete(Filter.Expression filterExpression) {
        }

        @Override
        public List<Document> similaritySearch(SearchRequest request) {
            return List.of();
        }
    }
}
