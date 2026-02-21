package com.qwerlty.myojbackendaiservice.chat.client;

import com.qwerlty.myojbackendaiservice.chat.model.QuestionContext;
import com.qwerlty.myojbackendaiservice.chat.model.SubmissionContext;
import com.qwerlty.myojbackendaiservice.chat.model.SubmissionQuery;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@FeignClient(name = "myoj-backend-question-service", path = "/api/question/inner")
public interface QuestionContextClient {

    @GetMapping("/get/id")
    QuestionContext getQuestion(@RequestParam("questionId") long questionId);

    @PostMapping("/question_submit/list")
    List<SubmissionContext> listSubmissions(@RequestBody SubmissionQuery query);
}
