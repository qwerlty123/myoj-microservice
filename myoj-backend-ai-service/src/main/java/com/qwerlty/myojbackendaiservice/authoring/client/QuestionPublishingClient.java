package com.qwerlty.myojbackendaiservice.authoring.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(
        name = "myoj-backend-question-service",
        contextId = "authoringQuestionPublishingClient",
        path = "/api/question/inner"
)
public interface QuestionPublishingClient {

    @PostMapping("/authoring/publish")
    Long publish(@RequestBody PublishQuestionRequest request);
}
