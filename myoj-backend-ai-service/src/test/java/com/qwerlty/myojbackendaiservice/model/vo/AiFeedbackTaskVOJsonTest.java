package com.qwerlty.myojbackendaiservice.model.vo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiFeedbackTaskVOJsonTest {

    @Test
    void serializesSnowflakeIdsAsStringsWithoutBrowserPrecisionLoss() throws Exception {
        AiFeedbackTaskVO task = new AiFeedbackTaskVO();
        task.setTaskId(1900000000000000001L);
        task.setSubmissionId(1900000000000000002L);
        task.setQuestionId(1900000000000000003L);

        String json = new ObjectMapper().writeValueAsString(task);

        assertThat(json)
                .contains("\"taskId\":\"1900000000000000001\"")
                .contains("\"submissionId\":\"1900000000000000002\"")
                .contains("\"questionId\":\"1900000000000000003\"");
    }
}
