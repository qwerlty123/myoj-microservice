package com.qwerlty.myojbackendaiservice.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class SpringAiConfig {

    @Bean
    public RetrievalAugmentationAdvisor retrievalAugmentationAdvisor(
            VectorStore vectorStore,
            @Value("${myoj.ai.knowledge.top-k:5}") int topK,
            @Value("${myoj.ai.knowledge.similarity-threshold:0.65}") double similarityThreshold) {
        VectorStoreDocumentRetriever retriever = VectorStoreDocumentRetriever.builder()
                .vectorStore(vectorStore)
                .topK(topK)
                .similarityThreshold(similarityThreshold)
                .build();
        return RetrievalAugmentationAdvisor.builder()
                .documentRetriever(retriever)
                .queryAugmenter(ContextualQueryAugmenter.builder().allowEmptyContext(true).build())
                .build();
    }

    @Bean
    @Primary
    public ChatClient aiChatClient(ChatModel chatModel,
                                   RetrievalAugmentationAdvisor retrievalAugmentationAdvisor) {
        return ChatClient.builder(chatModel)
                .defaultAdvisors(
                        retrievalAugmentationAdvisor,
                        ToolCallAdvisor.builder().build())
                .build();
    }

    /** 自动出题使用独立 ChatClient，不继承复盘的 RAG 和工具 Advisor。 */
    @Bean("authoringStructuredChatClient")
    public ChatClient authoringStructuredChatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel)
                .defaultOptions(OpenAiChatOptions.builder().temperature(0.15).build())
                .build();
    }

    /** 题目创作 Agent 仅启用 Tool Calling，明确不接入 RetrievalAugmentationAdvisor。 */
    @Bean("authoringAgentChatClient")
    public ChatClient authoringAgentChatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel)
                .defaultOptions(OpenAiChatOptions.builder().temperature(0.1).build())
                .defaultAdvisors(ToolCallAdvisor.builder().build())
                .build();
    }
}
