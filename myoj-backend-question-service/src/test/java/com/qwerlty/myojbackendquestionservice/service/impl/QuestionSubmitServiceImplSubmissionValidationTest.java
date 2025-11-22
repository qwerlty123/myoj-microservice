package com.qwerlty.myojbackendquestionservice.service.impl;

import com.qwerlty.myojbackendcommon.exception.BusinessException;
import com.qwerlty.myojbackendmodel.model.dto.questionsubmit.QuestionSubmitAddRequest;
import com.qwerlty.myojbackendmodel.model.entity.User;
import com.qwerlty.myojbackendquestionservice.service.QuestionService;
import com.qwerlty.myojbackendquestionservice.validation.SubmissionLanguageValidator;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class QuestionSubmitServiceImplSubmissionValidationTest {

    @Test
    void rejectsLanguageMismatchBeforeReadingQuestionOrCreatingSubmission() {
        QuestionSubmitServiceImpl service = new QuestionSubmitServiceImpl();
        QuestionService questionService = mock(QuestionService.class);
        ReflectionTestUtils.setField(service, "questionService", questionService);
        ReflectionTestUtils.setField(service, "submissionLanguageValidator", new SubmissionLanguageValidator());

        QuestionSubmitAddRequest request = new QuestionSubmitAddRequest();
        request.setQuestionId(1L);
        request.setLanguage("java");
        request.setCode("#include <bits/stdc++.h>\nusing namespace std;\nint main() { return 0; }");

        assertThatThrownBy(() -> service.doQuestionSubmit(request, new User()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请切换为 C++");
        verifyNoInteractions(questionService);
    }
}
