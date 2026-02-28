package com.qwerlty.myojbackendaiservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "myoj.ai")
public class AiAgentProperties {

    private Duration timeout = Duration.ofMinutes(10);
    private int maxPromptLength = 2_000;
    private int maxSteps = 10;
    private int memorySize = 16;
    private final Executor executor = new Executor();
    private final Executor chatExecutor = new Executor(8, 8, 32);
    private final Chat chat = new Chat();
    private final Authoring authoring = new Authoring();
    private final Sandbox sandbox = new Sandbox();
    private final Search search = new Search();
    private final Crawler crawler = new Crawler();

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public int getMaxPromptLength() {
        return maxPromptLength;
    }

    public void setMaxPromptLength(int maxPromptLength) {
        this.maxPromptLength = maxPromptLength;
    }

    public int getMaxSteps() {
        return maxSteps;
    }

    public void setMaxSteps(int maxSteps) {
        this.maxSteps = maxSteps;
    }

    public int getMemorySize() {
        return memorySize;
    }

    public void setMemorySize(int memorySize) {
        this.memorySize = memorySize;
    }

    public Executor getExecutor() {
        return executor;
    }

    public Executor getChatExecutor() {
        return chatExecutor;
    }

    public Chat getChat() {
        return chat;
    }

    public Authoring getAuthoring() {
        return authoring;
    }

    public Sandbox getSandbox() {
        return sandbox;
    }

    public Search getSearch() {
        return search;
    }

    public Crawler getCrawler() {
        return crawler;
    }

    public static class Executor {
        private int coreSize = 2;
        private int maxSize = 8;
        private int queueCapacity = 50;

        public Executor() {
        }

        private Executor(int coreSize, int maxSize, int queueCapacity) {
            this.coreSize = coreSize;
            this.maxSize = maxSize;
            this.queueCapacity = queueCapacity;
        }

        public int getCoreSize() {
            return coreSize;
        }

        public void setCoreSize(int coreSize) {
            this.coreSize = coreSize;
        }

        public int getMaxSize() {
            return maxSize;
        }

        public void setMaxSize(int maxSize) {
            this.maxSize = maxSize;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }
    }

    public static class Chat {
        private boolean enabled = true;
        private int retentionDays = 30;
        private Duration timeout = Duration.ofMinutes(30);
        private int maxHistoryMessages = 20;
        private int maxMessageLength = 4_000;
        private int maxUserCodeLength = 40_000;
        private int agentMaxSteps = 4;
        private int agentMaxDecisionRetries = 2;
        private int maxObservationChars = 1_200;
        private String archiveCron = "0 0/30 * * * ?";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getRetentionDays() {
            return retentionDays;
        }

        public void setRetentionDays(int retentionDays) {
            this.retentionDays = retentionDays;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public int getMaxHistoryMessages() {
            return maxHistoryMessages;
        }

        public void setMaxHistoryMessages(int maxHistoryMessages) {
            this.maxHistoryMessages = maxHistoryMessages;
        }

        public int getMaxMessageLength() {
            return maxMessageLength;
        }

        public void setMaxMessageLength(int maxMessageLength) {
            this.maxMessageLength = maxMessageLength;
        }

        public int getMaxUserCodeLength() {
            return maxUserCodeLength;
        }

        public void setMaxUserCodeLength(int maxUserCodeLength) {
            this.maxUserCodeLength = maxUserCodeLength;
        }

        public int getAgentMaxSteps() {
            return agentMaxSteps;
        }

        public void setAgentMaxSteps(int agentMaxSteps) {
            this.agentMaxSteps = agentMaxSteps;
        }

        public int getAgentMaxDecisionRetries() {
            return agentMaxDecisionRetries;
        }

        public void setAgentMaxDecisionRetries(int agentMaxDecisionRetries) {
            this.agentMaxDecisionRetries = agentMaxDecisionRetries;
        }

        public int getMaxObservationChars() {
            return maxObservationChars;
        }

        public void setMaxObservationChars(int maxObservationChars) {
            this.maxObservationChars = maxObservationChars;
        }

        public String getArchiveCron() {
            return archiveCron;
        }

        public void setArchiveCron(String archiveCron) {
            this.archiveCron = archiveCron;
        }
    }

    public static class Authoring {
        private boolean enabled = true;
        private int maxRepairCount = 3;
        private Duration staleAfter = Duration.ofMinutes(3);
        private String recoveryCron = "0 */1 * * * ?";
        private int pageSizeLimit = 50;
        private String graphVersion = "authoring-v2-hitl";
        private String promptVersion = "authoring-v1";
        private final RedisCheckpoint checkpoint = new RedisCheckpoint();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxRepairCount() {
            return maxRepairCount;
        }

        public void setMaxRepairCount(int maxRepairCount) {
            this.maxRepairCount = maxRepairCount;
        }

        public Duration getStaleAfter() {
            return staleAfter;
        }

        public void setStaleAfter(Duration staleAfter) {
            this.staleAfter = staleAfter;
        }

        public String getRecoveryCron() {
            return recoveryCron;
        }

        public void setRecoveryCron(String recoveryCron) {
            this.recoveryCron = recoveryCron;
        }

        public int getPageSizeLimit() {
            return pageSizeLimit;
        }

        public void setPageSizeLimit(int pageSizeLimit) {
            this.pageSizeLimit = pageSizeLimit;
        }

        public String getGraphVersion() {
            return graphVersion;
        }

        public void setGraphVersion(String graphVersion) {
            this.graphVersion = graphVersion;
        }

        public String getPromptVersion() {
            return promptVersion;
        }

        public void setPromptVersion(String promptVersion) {
            this.promptVersion = promptVersion;
        }

        public RedisCheckpoint getCheckpoint() {
            return checkpoint;
        }
    }

    public static class RedisCheckpoint {
        private String host = "127.0.0.1";
        private int port = 6379;
        private String password;
        private int database = 1;

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public int getDatabase() {
            return database;
        }

        public void setDatabase(int database) {
            this.database = database;
        }
    }

    public static class Sandbox {
        private String url = "http://localhost:8090/executeCode";
        private String secretKey;
        private Duration connectTimeout = Duration.ofSeconds(3);
        private Duration readTimeout = Duration.ofSeconds(120);
        private int maxCases = 12;
        private int maxCodeLength = 40_000;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getSecretKey() {
            return secretKey;
        }

        public void setSecretKey(String secretKey) {
            this.secretKey = secretKey;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }

        public int getMaxCases() {
            return maxCases;
        }

        public void setMaxCases(int maxCases) {
            this.maxCases = maxCases;
        }

        public int getMaxCodeLength() {
            return maxCodeLength;
        }

        public void setMaxCodeLength(int maxCodeLength) {
            this.maxCodeLength = maxCodeLength;
        }
    }

    public static class Search {
        private String apiUrl = "https://qianfan.baidubce.com/v2/ai_search/chat/completions";
        private String apiKey;
        private Duration timeout = Duration.ofSeconds(8);
        private int maxResults = 5;

        public String getApiUrl() {
            return apiUrl;
        }

        public void setApiUrl(String apiUrl) {
            this.apiUrl = apiUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public int getMaxResults() {
            return maxResults;
        }

        public void setMaxResults(int maxResults) {
            this.maxResults = maxResults;
        }
    }

    public static class Crawler {
        private Duration timeout = Duration.ofSeconds(8);
        private int maxBodyBytes = 512_000;
        private int maxTextLength = 12_000;
        private List<String> allowedHosts = new ArrayList<>(List.of("leetcode.cn"));

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public int getMaxBodyBytes() {
            return maxBodyBytes;
        }

        public void setMaxBodyBytes(int maxBodyBytes) {
            this.maxBodyBytes = maxBodyBytes;
        }

        public int getMaxTextLength() {
            return maxTextLength;
        }

        public void setMaxTextLength(int maxTextLength) {
            this.maxTextLength = maxTextLength;
        }

        public List<String> getAllowedHosts() {
            return allowedHosts;
        }

        public void setAllowedHosts(List<String> allowedHosts) {
            this.allowedHosts = allowedHosts == null ? new ArrayList<>() : new ArrayList<>(allowedHosts);
        }
    }
}
