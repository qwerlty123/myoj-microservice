package com.qwerlty.myojbackendjudgeservice.judge;

import cn.hutool.json.JSONUtil;
import com.qwerlty.myojbackendjudgeservice.judge.codesandbox.CodeSandbox;
import com.qwerlty.myojbackendjudgeservice.judge.codesandbox.CodeSandboxFactory;
import com.qwerlty.myojbackendmodel.model.codesandbox.ExecuteCodeResponse;
import com.qwerlty.myojbackendmodel.model.codesandbox.JudgeInfo;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JudgeServiceImplTest {

    private static final long SUBMIT_ID = 101L;

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
        prepareSubmission("[{\"input\":\"21\",\"output\":\"42\"}]", QuestionSubmitStatusEnum.SUCCEED);
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

        judgeService.doJudge(SUBMIT_ID);

        QuestionSubmit terminalUpdate = terminalUpdate();
        JudgeInfo persisted = JSONUtil.toBean(terminalUpdate.getJudgeInfo(), JudgeInfo.class);
        assertEquals(QuestionSubmitStatusEnum.SUCCEED.getValue(), terminalUpdate.getStatus());
        assertEquals(JudgeInfoMessageEnum.TIME_LIMIT_EXCEEDED.getValue(), persisted.getMessage());
        assertEquals(5_000L, persisted.getTime());
    }

    @Test
    void sandboxTransportFailureMovesSubmissionOutOfRunningState() {
        prepareSubmission("[{\"input\":\"\",\"output\":\"1\"}]", QuestionSubmitStatusEnum.FAILED);
        when(codeSandboxFactory.newInstance("remote")).thenReturn(codeSandbox);
        when(codeSandbox.executeCode(any())).thenThrow(new IllegalStateException("connection refused"));

        judgeService.doJudge(SUBMIT_ID);

        QuestionSubmit terminalUpdate = terminalUpdate();
        assertEquals(QuestionSubmitStatusEnum.FAILED.getValue(), terminalUpdate.getStatus());
        assertTrue(terminalUpdate.getLastError().contains("connection refused"));
    }

    @Test
    void emptyQuestionCasesFailWithoutCallingSandbox() {
        prepareSubmission("[]", QuestionSubmitStatusEnum.FAILED);

        judgeService.doJudge(SUBMIT_ID);

        QuestionSubmit terminalUpdate = terminalUpdate();
        assertEquals(QuestionSubmitStatusEnum.FAILED.getValue(), terminalUpdate.getStatus());
        assertTrue(terminalUpdate.getLastError().contains("测试用例"));
        verify(codeSandboxFactory, never()).newInstance(any());
    }

    private void prepareSubmission(String judgeCases, QuestionSubmitStatusEnum finalStatus) {
        QuestionSubmit initial = new QuestionSubmit();
        initial.setId(SUBMIT_ID);
        initial.setQuestionId(202L);
        initial.setStatus(QuestionSubmitStatusEnum.WAITING.getValue());
        initial.setLanguage("java");
        initial.setCode("public class Main { public static void main(String[] args) {} }");

        QuestionSubmit finished = new QuestionSubmit();
        finished.setId(SUBMIT_ID);
        finished.setStatus(finalStatus.getValue());

        Question question = new Question();
        question.setId(202L);
        question.setJudgeCase(judgeCases);
        question.setJudgeConfig("{\"timeLimit\":1000,\"memoryLimit\":256000}");

        when(questionFeignClient.getQuestionSubmitById(SUBMIT_ID)).thenReturn(initial, finished);
        when(questionFeignClient.getQuestionById(202L)).thenReturn(question);
        when(questionFeignClient.updateQuestionSubmitById(any())).thenReturn(true);
    }

    private QuestionSubmit terminalUpdate() {
        ArgumentCaptor<QuestionSubmit> captor = ArgumentCaptor.forClass(QuestionSubmit.class);
        verify(questionFeignClient, org.mockito.Mockito.times(2)).updateQuestionSubmitById(captor.capture());
        List<QuestionSubmit> updates = captor.getAllValues();
        assertEquals(QuestionSubmitStatusEnum.RUNNING.getValue(), updates.get(0).getStatus());
        return updates.get(1);
    }
}
