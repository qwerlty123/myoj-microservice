package com.qwerlty.myojbackendaiservice.generation.workflow;

import com.qwerlty.myojbackendaiservice.model.dto.generation.CaseEvidence;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;

/** Task-scoped quality evidence tool. The workflow supplies the evidence adapter. */
public class QualityEvidenceTools {
    private final EvidenceInspector inspector;
    private final WorkflowContext context;
    private int calls;

    public QualityEvidenceTools(EvidenceInspector inspector) {
        this(null, inspector);
    }

    public QualityEvidenceTools(WorkflowContext context, EvidenceInspector inspector) {
        this.context = context;
        this.inspector = inspector;
    }

    @Tool(description = "按用例下标获取校验器、Java、C++ 与 Oracle 的可信执行证据；每次最多五个下标。")
    public List<CaseEvidence> inspectCaseEvidence(
            @ToolParam(description = "需要复核的用例下标，最多5个") List<Integer> caseIndexes) {
        if (calls >= 3) throw new IllegalStateException("质检 Agent 已达到 3 次取证上限");
        if (caseIndexes == null || caseIndexes.isEmpty() || caseIndexes.size() > 5) {
            throw new IllegalArgumentException("每次必须检查 1 到 5 个用例下标");
        }
        calls++;
        long started = System.nanoTime();
        List<CaseEvidence> result = inspector.inspect(caseIndexes);
        if (context != null) {
            long latency = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            context.recordToolCall(new ToolCallTrace(calls, "inspectCaseEvidence",
                    caseIndexes.size(), result.size(), Math.max(0, caseIndexes.size() - result.size()),
                    latency, "EVIDENCE_RETURNED"));
            context.meterRegistry().counter("ai_authoring_tool_calls_total",
                    "type", context.taskType().name(),
                    "tool", "inspectCaseEvidence",
                    "round", Integer.toString(calls),
                    "accepted", Integer.toString(result.size()),
                    "rejected", Integer.toString(Math.max(0, caseIndexes.size() - result.size()))).increment();
        }
        return result;
    }

    @FunctionalInterface
    public interface EvidenceInspector {
        List<CaseEvidence> inspect(List<Integer> caseIndexes);
    }
}
