package com.qwerlty.myojbackendaiservice.authoring.graph;

import com.qwerlty.myojbackendaiservice.authoring.model.AuthoringProblemDraft;
import com.qwerlty.myojbackendaiservice.dto.JudgeCase;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class AuthoringDraftValidator {

    public List<String> validate(AuthoringProblemDraft draft) {
        List<String> errors = new ArrayList<>();
        if (draft == null) return List.of("题目草稿为空");
        if (!StringUtils.hasText(draft.title()) || draft.title().trim().length() > 80) {
            errors.add("题目标题必须为 1 至 80 个字符");
        }
        if (draft.difficulty() == null || draft.difficulty() < 0 || draft.difficulty() > 2) {
            errors.add("题目难度必须为 0、1 或 2");
        }
        if (!StringUtils.hasText(draft.content()) || draft.content().length() < 80) {
            errors.add("题面内容过短或为空");
        }
        if (!StringUtils.hasText(draft.answer()) || draft.answer().length() < 80) {
            errors.add("题解内容过短或为空");
        }
        String code = draft.referenceCode();
        if (!StringUtils.hasText(code) || !code.contains("class Main")
                || !code.contains("static void main")) {
            errors.add("Java 参考代码必须包含 class Main 和 static void main");
        }
        List<JudgeCase> cases = draft.judgeCase();
        if (cases.size() < 6 || cases.size() > 8) {
            errors.add("测试用例数量必须为 6 至 8 组");
        }
        Set<String> fingerprints = new HashSet<>();
        for (int index = 0; index < cases.size(); index++) {
            JudgeCase judgeCase = cases.get(index);
            if (judgeCase == null || !StringUtils.hasText(judgeCase.input())
                    || !StringUtils.hasText(judgeCase.output())) {
                errors.add("第 " + (index + 1) + " 组测试用例的 input/output 不能为空");
                continue;
            }
            String fingerprint = normalize(judgeCase.input()) + "\u0000" + normalize(judgeCase.output());
            if (!fingerprints.add(fingerprint)) {
                errors.add("第 " + (index + 1) + " 组测试用例与已有用例重复");
            }
        }
        AuthoringProblemDraft.JudgeConfig config = draft.judgeConfig();
        if (config.timeLimit() < 100 || config.timeLimit() > 15_000) {
            errors.add("时间限制必须在 100 至 15000 ms 之间");
        }
        if (config.memoryLimit() < 16_384 || config.memoryLimit() > 524_288) {
            errors.add("内存限制必须在 16384 至 524288 KB 之间");
        }
        if (config.stackLimit() < 256 || config.stackLimit() > 262_144) {
            errors.add("栈限制必须在 256 至 262144 KB 之间");
        }
        return List.copyOf(errors);
    }

    static String normalize(String value) {
        return value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n').stripTrailing();
    }
}
