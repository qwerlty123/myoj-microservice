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
import java.util.List;
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
        // 传入题目的提交 id，获取到对应的题目、提交信息（包含代码、编程语言等）
        QuestionSubmit questionSubmit = questionFeignClient.getQuestionSubmitById(questionSubmitId);
        if (questionSubmit == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "提交信息不存在");
        }
        Long questionId = questionSubmit.getQuestionId();
        Question question = questionFeignClient.getQuestionById(questionId);
        if (question == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "题目不存在");
        }
        // 如果题目提交状态不为等待中，就不用重复执行了
        if (!questionSubmit.getStatus().equals(QuestionSubmitStatusEnum.WAITING.getValue())) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "题目正在判题中");
        }
        // 更改判题（题目提交）的状态为 “判题中”，防止重复执行
        QuestionSubmit questionSubmitUpdate = new QuestionSubmit();
        questionSubmitUpdate.setId(questionSubmitId);
        questionSubmitUpdate.setStatus(QuestionSubmitStatusEnum.RUNNING.getValue());
        boolean update = questionFeignClient.updateQuestionSubmitById(questionSubmitUpdate);
        if (!update) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "题目已被处理");
        }
        // 调用沙箱，获取到执行结果
        CodeSandbox codeSandbox = codeSandboxFactory.newInstance(type);
        codeSandbox = new CodeSandboxProxy(codeSandbox);
        String language = questionSubmit.getLanguage();
        String code = questionSubmit.getCode();
        // 获取输入用例
        String judgeCaseStr = question.getJudgeCase();
        List<JudgeCase> judgeCaseList = JSONUtil.toList(judgeCaseStr, JudgeCase.class);
        List<String> inputList = judgeCaseList.stream().map(JudgeCase::getInput).collect(Collectors.toList());
        ExecuteCodeRequest executeCodeRequest = ExecuteCodeRequest.builder()
                .code(code)
                .language(language)
                .inputList(inputList)
                .build();
        //代理类执行去调用代码沙箱，得到输出结果
        ExecuteCodeResponse executeCodeResponse = codeSandbox.executeCode(executeCodeRequest);
        if (executeCodeResponse == null || executeCodeResponse.getStatus() == null) {
            throw new BusinessException(ErrorCode.API_REQUEST_ERROR, "代码沙箱返回为空");
        }
        Integer executeStatus = executeCodeResponse.getStatus();
        // 沙箱系统异常：直接标记为判题失败，避免误判为用户代码错误
        if (STATUS_SANDBOX_ERROR == executeStatus) {
            QuestionSubmit failedSubmit = new QuestionSubmit();
            failedSubmit.setId(questionSubmitId);
            failedSubmit.setStatus(QuestionSubmitStatusEnum.FAILED.getValue());
            failedSubmit.setLastError(executeCodeResponse.getMessage());
            JudgeInfo failedJudgeInfo = new JudgeInfo();
            failedJudgeInfo.setMessage(JudgeInfoMessageEnum.SYSTEM_ERROR.getValue());
            failedSubmit.setJudgeInfo(JSONUtil.toJsonStr(failedJudgeInfo));
            boolean failedUpdate = questionFeignClient.updateQuestionSubmitById(failedSubmit);
            if (!failedUpdate) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "题目状态更新错误");
            }
            return questionFeignClient.getQuestionSubmitById(questionSubmitId);
        }
        // 用户代码异常（编译错误 / 运行错误）：直接落库并结束判题
        if (STATUS_USER_CODE_ERROR == executeStatus) {
            QuestionSubmit doneSubmit = new QuestionSubmit();
            doneSubmit.setId(questionSubmitId);
            doneSubmit.setStatus(QuestionSubmitStatusEnum.SUCCEED.getValue());
            JudgeInfo errorJudgeInfo = new JudgeInfo();
            String sandboxJudgeMessage = executeCodeResponse.getJudgeInfo() == null ? null : executeCodeResponse.getJudgeInfo().getMessage();
            String message = executeCodeResponse.getMessage();
            doneSubmit.setLastError(message);
            if (StringUtils.containsIgnoreCase(sandboxJudgeMessage, "dangerous")) {
                errorJudgeInfo.setMessage(JudgeInfoMessageEnum.DANGEROUS_OPERATION.getValue());
            } else if (StringUtils.containsIgnoreCase(sandboxJudgeMessage, "compile")
                    || StringUtils.containsIgnoreCase(message, "compile")
                    || StringUtils.contains(message, "编译")) {
                errorJudgeInfo.setMessage(JudgeInfoMessageEnum.COMPILE_ERROR.getValue());
            } else {
                errorJudgeInfo.setMessage(JudgeInfoMessageEnum.RUNTIME_ERROR.getValue());
            }
            doneSubmit.setJudgeInfo(JSONUtil.toJsonStr(errorJudgeInfo));
            boolean doneUpdate = questionFeignClient.updateQuestionSubmitById(doneSubmit);
            if (!doneUpdate) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "题目状态更新错误");
            }
            return questionFeignClient.getQuestionSubmitById(questionSubmitId);
        }
        if (STATUS_SUCCESS != executeStatus) {
            throw new BusinessException(ErrorCode.API_REQUEST_ERROR, "未知代码沙箱状态: " + executeStatus);
        }
        List<String> outputList = executeCodeResponse.getOutputList();
        // 根据沙箱的执行结果，设置题目的判题状态和信息
        JudgeContext judgeContext = new JudgeContext();
        judgeContext.setJudgeInfo(executeCodeResponse.getJudgeInfo());
        judgeContext.setInputList(inputList);
        judgeContext.setOutputList(outputList);
        judgeContext.setJudgeCaseList(judgeCaseList);
        judgeContext.setQuestion(question);
        judgeContext.setQuestionSubmit(questionSubmit);
        JudgeInfo judgeInfo = judgeManager.doJudge(judgeContext);
        // 修改数据库中的判题结果
        questionSubmitUpdate = new QuestionSubmit();
        questionSubmitUpdate.setId(questionSubmitId);
        questionSubmitUpdate.setStatus(QuestionSubmitStatusEnum.SUCCEED.getValue());
        questionSubmitUpdate.setJudgeInfo(JSONUtil.toJsonStr(judgeInfo));
        questionSubmitUpdate.setLastError(null);
        update = questionFeignClient.updateQuestionSubmitById(questionSubmitUpdate);
        if (!update) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "题目状态更新错误");
        }
        QuestionSubmit questionSubmitResult = questionFeignClient.getQuestionSubmitById(questionSubmitId);
        return questionSubmitResult;
    }
}
