package com.qwerlty.myojbackendjudgeservice.judge.codesandbox;


import com.qwerlty.myojbackendmodel.model.codesandbox.ExecuteCodeRequest;
import com.qwerlty.myojbackendmodel.model.codesandbox.ExecuteCodeResponse;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class CodeSandboxProxy implements CodeSandbox {

    private final CodeSandbox codeSandbox;


    public CodeSandboxProxy(CodeSandbox codeSandbox) {
        this.codeSandbox = codeSandbox;
    }

    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {
        int caseCount = executeCodeRequest.getInputList() == null ? 0 : executeCodeRequest.getInputList().size();
        int codeLength = executeCodeRequest.getCode() == null ? 0 : executeCodeRequest.getCode().length();
        log.info("调用代码沙箱，language={}, caseCount={}, codeLength={}",
                executeCodeRequest.getLanguage(), caseCount, codeLength);
        ExecuteCodeResponse executeCodeResponse = codeSandbox.executeCode(executeCodeRequest);
        if (executeCodeResponse == null) {
            log.warn("代码沙箱返回 null");
        } else {
            int outputCount = executeCodeResponse.getOutputList() == null ? 0 : executeCodeResponse.getOutputList().size();
            log.info("代码沙箱响应，status={}, outputCount={}, judgeInfo={}",
                    executeCodeResponse.getStatus(), outputCount, executeCodeResponse.getJudgeInfo());
        }
        return executeCodeResponse;
    }
}
