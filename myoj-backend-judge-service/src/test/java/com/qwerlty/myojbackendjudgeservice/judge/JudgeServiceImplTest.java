package com.qwerlty.myojbackendjudgeservice.judge;

import cn.hutool.json.JSONUtil;
import com.qwerlty.myojbackendjudgeservice.judge.codesandbox.CodeSandbox;
import com.qwerlty.myojbackendjudgeservice.judge.codesandbox.CodeSandboxFactory;
import com.qwerlty.myojbackendjudgeservice.judge.codesandbox.SandboxConfigurationException;
import com.qwerlty.myojbackendmodel.model.codesandbox.ExecuteCodeResponse;
import com.qwerlty.myojbackendmodel.model.codesandbox.JudgeInfo;
import com.qwerlty.myojbackendmodel.model.dto.judge.JudgeTaskCompleteRequest;
import com.qwerlty.myojbackendmodel.model.dto.judge.JudgeTaskMessage;
import com.qwerlty.myojbackendmodel.model.dto.judge.JudgeTaskRetryRequest;
import com.qwerlty.myojbackendmodel.model.entity.Question;
import com.qwerlty.myojbackendmodel.model.entity.QuestionSubmit;
import com.qwerlty.myojbackendmodel.model.enums.JudgeInfoMessageEnum;
import com.qwerlty.myojbackendmodel.model.enums.QuestionSubmitStatusEnum;
import com.qwerlty.myojbackendserviceclient.client.QuestionFeignClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JudgeServiceImplTest {

    private static final long SUBMIT_ID = 101L;
    private static final int ATTEMPT = 1;

    @Mock
    private QuestionFeignClient questionFeignClient;

    @Mock
    private JudgeManager judgeManager;

    @Mock
    private CodeSandboxFactory codeSandboxFactory;

    @Mock
    private CodeSandbox codeSandbox;

    @InjectMocks
    private JudgeServiceImpl judgeService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(judgeService, "type", "remote");
    }

    @Test
    void timeoutFromSandboxIsPersistedAsTimeLimitExceeded() {
        prepareSubmission("[{\"input\":\"21\",\"output\":\"42\"}]", QuestionSubmitStatusEnum.SUCCEED, ATTEMPT);
        when(questionFeignClient.completeJudgeTask(any())).thenReturn(true);
        JudgeInfo sandboxJudgeInfo = new JudgeInfo();
        sandboxJudgeInfo.setMessage("Time Limit Exceeded");
        sandboxJudgeInfo.setTime(5_000L);
        when(codeSandboxFactory.newInstance("remote")).thenReturn(codeSandbox);
        when(codeSandbox.executeCode(any())).thenReturn(ExecuteCodeResponse.builder()
                .status(3)
                .message("程序执行超过时间限制")
                .outputList(Collections.emptyList())
                .judgeInfo(sandboxJudgeInfo)
                .build());

        judgeService.doJudge(message());

        JudgeTaskCompleteRequest completion = completion();
        JudgeInfo persisted = JSONUtil.toBean(completion.getJudgeInfo(), JudgeInfo.class);
        assertEquals(QuestionSubmitStatusEnum.SUCCEED.getValue(), completion.getStatus());
        assertEquals(ATTEMPT, completion.getJudgeAttempt());
        assertEquals(JudgeInfoMessageEnum.TIME_LIMIT_EXCEEDED.getValue(), persisted.getMessage());
        assertEquals(5_000L, persisted.getTime());
    }

    @Test
    void sandboxTransportFailureSchedulesNextAttempt() {
        prepareSubmission("[{\"input\":\"\",\"output\":\"1\"}]", QuestionSubmitStatusEnum.WAITING, ATTEMPT + 1);
        when(codeSandboxFactory.newInstance("remote")).thenReturn(codeSandbox);
        when(codeSandbox.executeCode(any())).thenThrow(new IllegalStateException("connection refused"));
        when(questionFeignClient.retryJudgeTask(any())).thenReturn(true);

        QuestionSubmit result = judgeService.doJudge(message());

        ArgumentCaptor<JudgeTaskRetryRequest> captor = ArgumentCaptor.forClass(JudgeTaskRetryRequest.class);
        verify(questionFeignClient).retryJudgeTask(captor.capture());
        assertEquals(ATTEMPT, captor.getValue().getJudgeAttempt());
        assertTrue(captor.getValue().getLastError().contains("connection refused"));
        assertEquals(QuestionSubmitStatusEnum.WAITING.getValue(), result.getStatus());
        assertEquals(ATTEMPT + 1, result.getJudgeAttempt());
        verify(questionFeignClient, never()).completeJudgeTask(any());
    }

    @Test
    void sandboxAuthenticationFailureStopsRetryingAndMarksSubmissionFailed() {
        prepareSubmission("[{\"input\":\"\",\"output\":\"1\"}]", QuestionSubmitStatusEnum.FAILED, ATTEMPT);
        when(codeSandboxFactory.newInstance("remote")).thenReturn(codeSandbox);
        when(codeSandbox.executeCode(any())).thenThrow(
                new SandboxConfigurationException("远程代码沙箱认证失败"));
        when(questionFeignClient.completeJudgeTask(any())).thenReturn(true);

        judgeService.doJudge(message());

        JudgeTaskCompleteRequest completion = completion();
        assertEquals(QuestionSubmitStatusEnum.FAILED.getValue(), completion.getStatus());
        assertTrue(completion.getLastError().contains("认证失败"));
        verify(questionFeignClient, never()).retryJudgeTask(any());
    }

    @Test
    void emptyQuestionCasesFailWithoutCallingSandbox() {
        prepareSubmission("[]", QuestionSubmitStatusEnum.FAILED, ATTEMPT);
        when(questionFeignClient.completeJudgeTask(any())).thenReturn(true);

        judgeService.doJudge(message());

        JudgeTaskCompleteRequest completion = completion();
        assertEquals(QuestionSubmitStatusEnum.FAILED.getValue(), completion.getStatus());
        assertEquals(ATTEMPT, completion.getJudgeAttempt());
        assertTrue(completion.getLastError().contains("测试用例"));
        verify(codeSandboxFactory, never()).newInstance(any());
    }

    @Test
    void staleAttemptDoesNotClaimOrExecute() {
        QuestionSubmit current = submission(QuestionSubmitStatusEnum.WAITING, ATTEMPT + 1);
        when(questionFeignClient.getQuestionSubmitById(SUBMIT_ID)).thenReturn(current);

        QuestionSubmit result = judgeService.doJudge(message());

        assertEquals(ATTEMPT + 1, result.getJudgeAttempt());
        verify(questionFeignClient, never()).claimJudgeTask(any());
        verify(codeSandboxFactory, never()).newInstance(any());
    }

    private void prepareSubmission(String judgeCases,
                                   QuestionSubmitStatusEnum finalStatus,
                                   int finalAttempt) {
        QuestionSubmit initial = submission(QuestionSubmitStatusEnum.WAITING, ATTEMPT);
        QuestionSubmit finished = submission(finalStatus, finalAttempt);

        Question question = new Question();
        question.setId(202L);
        question.setJudgeCase(judgeCases);
        question.setJudgeConfig("{\"timeLimit\":1000,\"memoryLimit\":256000}");

        when(questionFeignClient.getQuestionSubmitById(SUBMIT_ID)).thenReturn(initial, finished);
        when(questionFeignClient.getQuestionById(202L)).thenReturn(question);
        when(questionFeignClient.claimJudgeTask(any())).thenReturn(true);
    }

    private QuestionSubmit submission(QuestionSubmitStatusEnum status, int attempt) {
        QuestionSubmit submission = new QuestionSubmit();
        submission.setId(SUBMIT_ID);
        submission.setQuestionId(202L);
        submission.setStatus(status.getValue());
        submission.setJudgeAttempt(attempt);
        submission.setLanguage("java");
        submission.setCode("public class Main { public static void main(String[] args) {} }");
        return submission;
    }

    private JudgeTaskMessage message() {
        return JudgeTaskMessage.builder()
                .messageId("event-1")
                .eventType(JudgeTaskMessage.EVENT_TYPE)
                .schemaVersion(JudgeTaskMessage.SCHEMA_VERSION)
                .submissionId(SUBMIT_ID)
                .judgeAttempt(ATTEMPT)
                .build();
    }

    private JudgeTaskCompleteRequest completion() {
        ArgumentCaptor<JudgeTaskCompleteRequest> captor = ArgumentCaptor.forClass(JudgeTaskCompleteRequest.class);
        verify(questionFeignClient).completeJudgeTask(captor.capture());
        return captor.getValue();
    }
}
