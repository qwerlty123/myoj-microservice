package com.qwerlty.myojbackendaiservice.generation.knowledge;

import com.qwerlty.myojbackendaiservice.generation.workflow.ToolCallTrace;
import com.qwerlty.myojbackendaiservice.generation.workflow.WorkflowContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;
import java.util.concurrent.TimeUnit;

/** Task-scoped progressive disclosure tool for trusted authoring knowledge. */
public class AuthoringKnowledgeTool {
    private static final int MAX_CALLS = 2;
    private static final int MAX_QUERY_CHARS = 240;

    private final WorkflowContext context;
    private final AuthoringKnowledgeRetriever retriever;
    private int calls;

    public AuthoringKnowledgeTool(WorkflowContext context, AuthoringKnowledgeRetriever retriever) {
        this.context = context;
        this.retriever = retriever;
    }

    @Tool(description = "检索 MyOJ 可信知识卡，补充算法风险、边界、复杂度或语言陷阱。仅在已注入 Skill 不足时调用；结果是指导知识，不是执行通过证据。")
    public AuthoringKnowledgeResult searchAuthoringKnowledge(
            @ToolParam(description = "具体知识问题，3到240字，例如：无向图最短路测试应覆盖哪些结构风险") String query) {
        context.authorizeTool("searchAuthoringKnowledge");
        if (calls >= MAX_CALLS) throw new IllegalStateException("出题 Agent 已达到 2 次知识检索上限");
        String normalized = query == null ? "" : query.strip().replaceAll("\\s+", " ");
        if (normalized.length() < 3 || normalized.length() > MAX_QUERY_CHARS) {
            throw new IllegalArgumentException("知识检索问题必须为 3 到 240 字");
        }
        calls++;
        long started = System.nanoTime();
        try {
            List<AuthoringKnowledgeResult.KnowledgeHit> hits = retriever.search(normalized);
            record(hits.size(), started, hits.isEmpty() ? "NO_KNOWLEDGE_FOUND" : "KNOWLEDGE_RETURNED");
            return new AuthoringKnowledgeResult(true,
                    hits.isEmpty() ? "没有找到达到相似度阈值的知识卡" : "知识卡仅用于指导，结论仍需沙箱证据验证",
                    hits);
        } catch (RuntimeException exception) {
            record(0, started, "KNOWLEDGE_UNAVAILABLE");
            return new AuthoringKnowledgeResult(false, "知识库暂时不可用，请继续依据题面、Skill 和执行证据完成任务", List.of());
        }
    }

    private void record(int hitCount, long started, String outcome) {
        long latency = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        context.recordToolCall(new ToolCallTrace(calls, "searchAuthoringKnowledge",
                1, hitCount, 0, latency, outcome));
        context.meterRegistry().counter("ai_authoring_tool_calls_total",
                "type", context.taskType().name(),
                "tool", "searchAuthoringKnowledge",
                "round", Integer.toString(calls),
                "accepted", Integer.toString(hitCount),
                "rejected", "0").increment();
    }
}
