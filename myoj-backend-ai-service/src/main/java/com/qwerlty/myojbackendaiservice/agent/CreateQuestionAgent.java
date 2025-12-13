package com.qwerlty.myojbackendaiservice.agent;

import com.qwerlty.myojbackendaiservice.config.AiAgentProperties;
import com.qwerlty.myojbackendaiservice.config.ToolFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

@Component
public class CreateQuestionAgent extends ToolCallAgent {

    private static final String SYSTEM_PROMPT = """
            你是 MyOJ 的算法题生成专家。用户会给出题目主题和难度，你要生成一题可直接录入 OJ 的原创题目。

            你可以使用搜索和网页读取工具了解常见题型，但不得复制现有题目的成段表述；最终题目必须重新设计。
            你必须给出：
            1. 题目描述（Markdown）：包含题目名称、描述、输入格式、输出格式、示例和边界说明，禁止泄露解法。
            2. 解法教学（Markdown，仅 Java）：包含思路、步骤、可运行的 ACM 模式 Main 类、时间和空间复杂度。
            3. 6 至 8 组测试用例：至少 3 组常规数据和 3 组边界数据，每组都只有一个 input 和一个 output。

            在结束前必须使用 doCodeTest 一次性提交全部测试用例，由后端运行代码并核对实际输出与预期 output。
            只有后端返回全部用例验证成功，才能使用同一批测试用例调用 doTerminate；若失败，应修正后重新验证。
            """;

    public CreateQuestionAgent(ChatModel chatModel,
                               ToolFactory toolFactory,
                               AiAgentProperties properties) {
        super(chatModel, toolFactory, properties);
    }

    @Override
    protected String systemPrompt() {
        return SYSTEM_PROMPT;
    }
}
