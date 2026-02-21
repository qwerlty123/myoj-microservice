package com.qwerlty.myojbackendaiservice.agent;

import com.qwerlty.myojbackendaiservice.config.AiAgentProperties;
import com.qwerlty.myojbackendaiservice.config.ToolFactory;
import com.qwerlty.myojbackendaiservice.enums.MessageType;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public abstract class ToolCallAgent extends ReActAgent {

    private final ChatClient chatClient;
    private final ToolFactory toolFactory;
    private final AiAgentProperties properties;
    private final ToolCallingManager toolCallingManager = ToolCallingManager.builder().build();

    protected ToolCallAgent(ChatModel chatModel,
                            ToolFactory toolFactory,
                            AiAgentProperties properties) {
        super(properties);
        this.chatClient = ChatClient.builder(chatModel).build();
        this.toolFactory = toolFactory;
        this.properties = properties;
    }

    @Override
    protected String thinkAndAct(String difficulty, String userPrompt, GenerationSession session) {
        Object[] tools = toolFactory.createQuestionTools(session);
        ToolCallback[] callbacks = ToolCallbacks.from(tools);
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .toolCallbacks(callbacks)
                .internalToolExecutionEnabled(false)
                .build();
        String userMessage = """
                难度：%s
                用户需求：%s

                请主动选择必要的工具完成任务。生成测试用例后必须调用 doCodeTest 验证 Java 参考解，
                确认输出正确后调用 doTerminate 提交最终题目。不要在普通文本中伪造工具执行结果。
                """.formatted(difficulty, userPrompt);
        List<Message> conversation = new ArrayList<>();
        conversation.add(new SystemMessage(systemPrompt()));

        String lastResult = "";
        for (int step = 1; step <= properties.getMaxSteps() && !session.isTerminated(); step++) {
            conversation.add(new UserMessage(userMessage));
            conversation = trimConversation(conversation);
            Prompt prompt = new Prompt(conversation, options);
            ChatResponse response = chatClient.prompt(prompt).call().chatResponse();
            if (response == null || response.getResult() == null) {
                lastResult = "模型没有返回内容";
                session.emit(MessageType.TOOL, "Step " + step + ": " + lastResult);
                continue;
            }

            AssistantMessage assistant = response.getResult().getOutput();
            if (assistant.getToolCalls().isEmpty()) {
                conversation.add(assistant);
                lastResult = assistant.getText() == null || assistant.getText().isBlank()
                        ? "思考完成 - 无需行动"
                        : assistant.getText();
                session.emit(MessageType.TOOL, "Step " + step + ": " + lastResult);
                continue;
            }

            ToolExecutionResult execution = toolCallingManager.executeToolCalls(prompt, response);
            conversation = new ArrayList<>(execution.conversationHistory());
            ToolResponseMessage toolResponse = (ToolResponseMessage) conversation.get(conversation.size() - 1);
            lastResult = toolResponse.getResponses().stream()
                    .map(item -> "工具 " + item.name() + " 完成了它的任务！结果: " + item.responseData())
                    .collect(Collectors.joining("\n"));
            session.emit(MessageType.TOOL, "Step " + step + ": " + lastResult);
        }
        return lastResult;
    }

    private List<Message> trimConversation(List<Message> conversation) {
        int maxMessages = Math.max(2, properties.getMemorySize());
        if (conversation.size() <= maxMessages + 1) {
            return conversation;
        }
        List<Message> trimmed = new ArrayList<>();
        trimmed.add(conversation.get(0));
        trimmed.addAll(conversation.subList(conversation.size() - maxMessages, conversation.size()));
        return trimmed;
    }

    protected abstract String systemPrompt();
}
