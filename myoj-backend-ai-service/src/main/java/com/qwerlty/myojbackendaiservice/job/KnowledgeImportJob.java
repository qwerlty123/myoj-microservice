package com.qwerlty.myojbackendaiservice.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "myoj.ai.knowledge.initialize", havingValue = "true")
public class KnowledgeImportJob implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeImportJob.class);
    private static final int PROVIDER_MAX_EMBEDDING_BATCH_SIZE = 10;

    private final VectorStore vectorStore;
    private final String knowledgeVersion;
    private final int overlapChars;
    private final int embeddingBatchSize;

    public KnowledgeImportJob(VectorStore vectorStore,
                              @Value("${myoj.ai.knowledge-version:v1}") String knowledgeVersion,
                              @Value("${myoj.ai.knowledge.overlap-chars:400}") int overlapChars,
                              @Value("${myoj.ai.knowledge.embedding-batch-size:10}") int embeddingBatchSize) {
        this.vectorStore = vectorStore;
        this.knowledgeVersion = knowledgeVersion;
        this.overlapChars = Math.max(0, Math.min(1000, overlapChars));
        this.embeddingBatchSize = Math.max(1,
                Math.min(PROVIDER_MAX_EMBEDDING_BATCH_SIZE, embeddingBatchSize));
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        List<Resource> resources = new ArrayList<>();
        resources.addAll(List.of(resolver.getResources("classpath*:knowledge/**/*.md")));
        resources.addAll(List.of(resolver.getResources("classpath*:authoring-skills/*/references/**/*.md")));
        if (resources.isEmpty()) {
            log.warn("No knowledge documents found, skip import");
            return;
        }

        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(600)
                .withMinChunkSizeChars(120)
                .withMinChunkLengthToEmbed(20)
                .withMaxNumChunks(100)
                .withKeepSeparator(true)
                .build();

        List<Document> chunks = new ArrayList<>();
        for (Resource resource : resources) {
            String raw = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
            KnowledgeCard card = parseCard(resource, raw);
            Document original = new Document(card.content(), card.metadata());
            List<Document> splitDocuments = splitter.apply(List.of(original));
            String previousText = "";
            for (int index = 0; index < splitDocuments.size(); index++) {
                Document split = splitDocuments.get(index);
                String text = split.getText();
                if (index > 0 && overlapChars > 0 && StringUtils.hasText(previousText)) {
                    int start = Math.max(0, previousText.length() - overlapChars);
                    text = previousText.substring(start) + "\n" + text;
                }
                Map<String, Object> metadata = new HashMap<>(card.metadata());
                metadata.put("chunkIndex", index);
                text = "[docId=" + metadata.get("docId") + "][title=" + metadata.get("title") + "]\n" + text;
                String contentHash = sha256(text);
                String idSeed = knowledgeVersion + ":" + metadata.get("docId") + ":" + index + ":" + contentHash;
                String id = UUID.nameUUIDFromBytes(idSeed.getBytes(StandardCharsets.UTF_8)).toString();
                metadata.put("contentHash", contentHash);
                metadata.put("chunkId", id);
                chunks.add(new Document(id, text, metadata));
                previousText = split.getText();
            }
        }

        List<String> ids = chunks.stream().map(Document::getId).toList();
        vectorStore.delete(ids);
        for (int start = 0; start < chunks.size(); start += embeddingBatchSize) {
            int end = Math.min(start + embeddingBatchSize, chunks.size());
            vectorStore.add(List.copyOf(chunks.subList(start, end)));
        }
        log.info("Knowledge import finished: version={}, files={}, chunks={}",
                knowledgeVersion, resources.size(), chunks.size());
    }

    private KnowledgeCard parseCard(Resource resource, String raw) throws Exception {
        String path = resource.getURL().toString();
        String body = raw;
        Map<String, Object> metadata = new HashMap<>();
        if (raw.startsWith("---")) {
            int end = raw.indexOf("\n---", 3);
            if (end > 0) {
                String header = raw.substring(3, end).trim();
                for (String line : header.split("\\R")) {
                    int separator = line.indexOf(':');
                    if (separator > 0) {
                        metadata.put(line.substring(0, separator).trim(),
                                line.substring(separator + 1).trim());
                    }
                }
                body = raw.substring(end + 4).trim();
            }
        }
        String filename = resource.getFilename() == null ? path : resource.getFilename();
        metadata.putIfAbsent("docId", filename.replace(".md", ""));
        metadata.putIfAbsent("title", metadata.get("docId"));
        metadata.putIfAbsent("topic", "general");
        metadata.putIfAbsent("language", "common");
        metadata.putIfAbsent("errorType", "COMMON");
        metadata.putIfAbsent("source", "MyOJ Knowledge Base");
        metadata.putIfAbsent("audience", "feedback");
        metadata.put("version", knowledgeVersion);
        metadata.put("path", path);
        return new KnowledgeCard(path, body, metadata);
    }

    private String sha256(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private record KnowledgeCard(String path, String content, Map<String, Object> metadata) {
    }
}
