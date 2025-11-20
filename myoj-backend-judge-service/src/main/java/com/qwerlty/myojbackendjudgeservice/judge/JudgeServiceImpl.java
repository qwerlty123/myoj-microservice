package com.qwerlty.myojbackendjudgeservice.judge;

import cn.hutool.json.JSONUtil;
import com.qwerlty.myojbackendcommon.common.ErrorCode;
import com.qwerlty.myojbackendcommon.exception.BusinessException;
import com.qwerlty.myojbackendjudgeservice.judge.codesandbox.CodeSandbox;
import com.qwerlty.myojbackendjudgeservice.judge.codesandbox.CodeSandboxFactory;
import com.qwerlty.myojbackendjudgeservice.judge.codesandbox.CodeSandboxProxy;
import com.qwerlty.myojbackendjudgeservice.judge.strategy.JudgeContext;
import com.qwerlty.myojbackendmodel.model.codesandbox.ExecuteCodeRequest;
import com.qwerlty.myojbackendmodel.model.codesandbox.ExecuteCodeResponse;
import com.qwerlty.myojbackendmodel.model.codesandbox.JudgeInfo;
import com.qwerlty.myojbackendmodel.model.dto.judge.JudgeTaskClaimRequest;
import com.qwerlty.myojbackendmodel.model.dto.judge.JudgeTaskCompleteRequest;
import com.qwerlty.myojbackendmodel.model.dto.judge.JudgeTaskMessage;
import com.qwerlty.myojbackendmodel.model.dto.judge.JudgeTaskRetryRequest;
import com.qwerlty.myojbackendmodel.model.dto.question.JudgeCase;
import com.qwerlty.myojbackendmodel.model.entity.Question;
import com.qwerlty.myojbackendmodel.model.entity.QuestionSubmit;
import com.qwerlty.myojbackendmodel.model.enums.JudgeInfoMessageEnum;
import com.qwerlty.myojbackendmodel.model.enums.QuestionSubmitStatusEnum;
import com.qwerlty.myojbackendserviceclient.client.QuestionFeignClient;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class JudgeServiceImpl implements JudgeService {

    private static final int STATUS_SUCCESS = 1;
    private static final int STATUS_SANDBOX_ERROR = 2;
    private static final int STATUS_USER_CODE_ERROR = 3;

    @Resource
    private QuestionFeignClient questionFeignClient;

    @Resource
    private JudgeManager judgeManager;

    @Resource
    private CodeSandboxFactory codeSandboxFactory;

    @Value("${codesandbox.type:example}")
    private String type;

    @Override
    public QuestionSubmit doJudge(Long questionSubmitId) {
        QuestionSubmit current = questionFeignClient.getQuestionSubmitById(questionSubmitId);
        if (current == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "提交信息不存在");
        }
        JudgeTaskMessage message = JudgeTaskMessage.builder()
                .messageId("internal-" + UUID.randomUUID())
                .eventType(JudgeTaskMessage.EVENT_TYPE)
                .schemaVersion(JudgeTaskMessage.SCHEMA_VERSION)
                .submissionId(questionSubmitId)
                .judgeAttempt(currentAttempt(current))
                .createdAt(new Date())
                .build();
        return doJudge(message);
    }

    @Override
    public QuestionSubmit doJudge(JudgeTaskMessage message) {
        Long submissionId = message.getSubmissionId();
        Integer attempt = message.getJudgeAttempt();
        QuestionSubmit questionSubmit = questionFeignClient.getQuestionSubmitById(submissionId);
        if (questionSubmit == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "提交信息不存在");
        }

        if (!Objects.equals(questionSubmit.getStatus(), QuestionSubmitStatusEnum.WAITING.getValue())
                || !Objects.equals(currentAttempt(questionSubmit), attempt)) {
            return questionSubmit;
        }

        Question question = questionFeignClient.getQuestionById(questionSubmit.getQuestionId());
        boolean claimed = Boolean.TRUE.equals(questionFeignClient.claimJudgeTask(
                new JudgeTaskClaimRequest(submissionId, attempt)));
        if (!claimed) {
            return latestOr(questionSubmit);
        }
        if (question == null) {
            return finishPermanently(submissionId, attempt, "题目不存在");
        }

        List<JudgeCase> judgeCaseList;
        try {
            judgeCaseList = JSONUtil.toList(question.getJudgeCase(), JudgeCase.class);
        } catch (Exception e) {
            return finishPermanently(submissionId, attempt, "题目测试用例配置无法解析");
        }
        if (judgeCaseList == null || judgeCaseList.isEmpty()) {
            return finishPermanently(submissionId, attempt, "题目测试用例不能为空");
        }

        List<String> inputList = judgeCaseList.stream().map(JudgeCase::getInput).collect(Collectors.toList());
        ExecuteCodeRequest executeCodeRequest = ExecuteCodeRequest.builder()
                .code(questionSubmit.getCode())
                .language(questionSubmit.getLanguage())
                .inputList(inputList)
                .build();

        ExecuteCodeResponse executeCodeResponse;
        try {
            CodeSandbox codeSandbox = new CodeSandboxProxy(codeSandboxFactory.newInstance(type));
            executeCodeResponse = codeSandbox.executeCode(executeCodeRequest);
        } catch (Exception e) {
            return retryAfterSystemError(submissionId, attempt, "代码沙箱调用失败: " + safeMessage(e));
        }
        if (executeCodeResponse == null || executeCodeResponse.getStatus() == null) {
            return retryAfterSystemError(submissionId, attempt, "代码沙箱返回为空");
        }

        Integer executeStatus = executeCodeResponse.getStatus();
        if (STATUS_SANDBOX_ERROR == executeStatus) {
            return retryAfterSystemError(submissionId, attempt, executeCodeResponse.getMessage());
        }
        if (STATUS_USER_CODE_ERROR == executeStatus) {
            return completeUserCodeError(submissionId, attempt, executeCodeResponse);
        }
        if (STATUS_SUCCESS != executeStatus) {
            return retryAfterSystemError(submissionId, attempt, "未知代码沙箱状态: " + executeStatus);
        }

        JudgeContext judgeContext = new JudgeContext();
        judgeContext.setJudgeInfo(executeCodeResponse.getJudgeInfo());
        judgeContext.setInputList(inputList);
        judgeContext.setOutputList(executeCodeResponse.getOutputList());
        judgeContext.setJudgeCaseList(judgeCaseList);
        judgeContext.setQuestion(question);
        judgeContext.setQuestionSubmit(questionSubmit);
        JudgeInfo judgeInfo;
        try {
            judgeInfo = judgeManager.doJudge(judgeContext);
        } catch (Exception e) {
            return retryAfterSystemError(submissionId, attempt, "判题策略执行失败: " + safeMessage(e));
        }
        return complete(submissionId, attempt, QuestionSubmitStatusEnum.SUCCEED.getValue(),
                JSONUtil.toJsonStr(judgeInfo), null);
    }

    private QuestionSubmit completeUserCodeError(Long submissionId,
                                                  Integer attempt,
                                                  ExecuteCodeResponse response) {
        JudgeInfo errorJudgeInfo = new JudgeInfo();
        JudgeInfo sandboxJudgeInfo = response.getJudgeInfo();
        String sandboxJudgeMessage = sandboxJudgeInfo == null ? null : sandboxJudgeInfo.getMessage();
        if (sandboxJudgeInfo != null) {
            errorJudgeInfo.setTime(sandboxJudgeInfo.getTime());
            errorJudgeInfo.setMemory(sandboxJudgeInfo.getMemory());
        }
        String message = response.getMessage();
        if (StringUtils.containsIgnoreCase(sandboxJudgeMessage, "dangerous")) {
            errorJudgeInfo.setMessage(JudgeInfoMessageEnum.DANGEROUS_OPERATION.getValue());
        } else if (StringUtils.containsIgnoreCase(sandboxJudgeMessage, "time limit")
                || StringUtils.contains(message, "超时")) {
            errorJudgeInfo.setMessage(JudgeInfoMessageEnum.TIME_LIMIT_EXCEEDED.getValue());
        } else if (StringUtils.containsIgnoreCase(sandboxJudgeMessage, "output limit")
                || StringUtils.contains(message, "输出超过")) {
            errorJudgeInfo.setMessage(JudgeInfoMessageEnum.OUTPUT_LIMIT_EXCEEDED.getValue());
        } else if (StringUtils.containsIgnoreCase(sandboxJudgeMessage, "compile")
                || StringUtils.containsIgnoreCase(message, "compile")
                || StringUtils.contains(message, "编译")) {
            errorJudgeInfo.setMessage(JudgeInfoMessageEnum.COMPILE_ERROR.getValue());
        } else {
            errorJudgeInfo.setMessage(JudgeInfoMessageEnum.RUNTIME_ERROR.getValue());
        }
        return complete(submissionId, attempt, QuestionSubmitStatusEnum.SUCCEED.getValue(),
                JSONUtil.toJsonStr(errorJudgeInfo), message);
    }

    private QuestionSubmit finishPermanently(Long submissionId, Integer attempt, String lastError) {
        JudgeInfo judgeInfo = new JudgeInfo();
        judgeInfo.setMessage(JudgeInfoMessageEnum.SYSTEM_ERROR.getValue());
        return complete(submissionId, attempt, QuestionSubmitStatusEnum.FAILED.getValue(),
                JSONUtil.toJsonStr(judgeInfo), StringUtils.defaultIfBlank(lastError, "判题配置错误"));
    }

    private QuestionSubmit retryAfterSystemError(Long submissionId, Integer attempt, String lastError) {
        boolean transitioned = Boolean.TRUE.equals(questionFeignClient.retryJudgeTask(
                new JudgeTaskRetryRequest(submissionId, attempt,
                        StringUtils.defaultIfBlank(lastError, "代码沙箱系统错误"))));
        QuestionSubmit latest = questionFeignClient.getQuestionSubmitById(submissionId);
        if (!transitioned && isSameRunningAttempt(latest, attempt)) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "判题重试状态更新失败");
        }
        return latest == null ? fallback(submissionId, attempt, QuestionSubmitStatusEnum.FAILED.getValue()) : latest;
    }

    private QuestionSubmit complete(Long submissionId,
                                    Integer attempt,
                                    Integer status,
                                    String judgeInfo,
                                    String lastError) {
        JudgeTaskCompleteRequest request = new JudgeTaskCompleteRequest();
        request.setSubmissionId(submissionId);
        request.setJudgeAttempt(attempt);
        request.setStatus(status);
        request.setJudgeInfo(judgeInfo);
        request.setLastError(lastError);
        boolean completed = Boolean.TRUE.equals(questionFeignClient.completeJudgeTask(request));
        QuestionSubmit latest = questionFeignClient.getQuestionSubmitById(submissionId);
        if (!completed && isSameRunningAttempt(latest, attempt)) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "判题结果状态更新失败");
        }
        return latest == null ? fallback(submissionId, attempt, status) : latest;
    }

    private boolean isSameRunningAttempt(QuestionSubmit submit, Integer attempt) {
        return submit != null
                && Objects.equals(submit.getStatus(), QuestionSubmitStatusEnum.RUNNING.getValue())
                && Objects.equals(currentAttempt(submit), attempt);
    }

    private QuestionSubmit latestOr(QuestionSubmit fallback) {
        QuestionSubmit latest = questionFeignClient.getQuestionSubmitById(fallback.getId());
        return latest == null ? fallback : latest;
    }

    private QuestionSubmit fallback(Long submissionId, Integer attempt, Integer status) {
        QuestionSubmit fallback = new QuestionSubmit();
        fallback.setId(submissionId);
        fallback.setJudgeAttempt(attempt);
        fallback.setStatus(status);
        return fallback;
    }

    private int currentAttempt(QuestionSubmit submit) {
        return submit.getJudgeAttempt() == null ? 1 : submit.getJudgeAttempt();
    }

    private String safeMessage(Throwable exception) {
        Throwable cause = exception.getCause() == null ? exception : exception.getCause();
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
