package com.qwerlty.myojbackendaiservice.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class SpringAiConfig {

    @Bean
    public RestClientCustomizer aiModelTimeoutCustomizer(
            @Value("${myoj.ai.generation.model-connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${myoj.ai.generation.model-read-timeout-ms:120000}") int readTimeoutMs) {
        return builder -> {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(connectTimeoutMs);
            factory.setReadTimeout(readTimeoutMs);
            builder.requestFactory(factory);
        };
    }

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
    public ChatClient authoringStructuredChatClient(
            OpenAiChatModel chatModel,
            @Value("${spring.ai.openai.chat.base-url:https://api.openai.com}") String baseUrl,
            @Value("${spring.ai.openai.chat.api-key:${spring.ai.openai.api-key}}") String apiKey,
            @Value("${myoj.ai.generation.model-connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${myoj.ai.generation.structured-model-read-timeout-ms:90000}") int readTimeoutMs) {
        return ChatClient.builder(timedModel(chatModel, baseUrl, apiKey, connectTimeoutMs, readTimeoutMs))
                .defaultOptions(OpenAiChatOptions.builder().temperature(0.15).build())
                .build();
    }

    /** 题目创作 Agent 仅启用 Tool Calling，明确不接入 RetrievalAugmentationAdvisor。 */
    @Bean("authoringAgentChatClient")
    public ChatClient authoringAgentChatClient(
            OpenAiChatModel chatModel,
            @Value("${spring.ai.openai.chat.base-url:https://api.openai.com}") String baseUrl,
            @Value("${spring.ai.openai.chat.api-key:${spring.ai.openai.api-key}}") String apiKey,
            @Value("${myoj.ai.generation.model-connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${myoj.ai.generation.agent-model-read-timeout-ms:120000}") int readTimeoutMs) {
        return authoringAgentChatClient(timedModel(chatModel, baseUrl, apiKey, connectTimeoutMs, readTimeoutMs));
    }

    public ChatClient authoringAgentChatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel)
                .defaultOptions(OpenAiChatOptions.builder().temperature(0.1).build())
                .defaultAdvisors(ToolCallAdvisor.builder().build())
                .build();
    }

    private ChatModel timedModel(OpenAiChatModel source,
                                 String baseUrl,
                                 String apiKey,
                                 int connectTimeoutMs,
                                 int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .restClientBuilder(RestClient.builder().requestFactory(factory))
                .build();
        return source.mutate().openAiApi(api).build();
    }
}
