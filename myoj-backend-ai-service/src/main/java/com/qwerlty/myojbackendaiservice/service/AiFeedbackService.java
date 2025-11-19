package com.qwerlty.myojbackendaiservice.service;

import com.qwerlty.myojbackendaiservice.model.vo.AiFeedbackTaskVO;

public interface AiFeedbackService {
    AiFeedbackTaskVO createTask(Long submissionId, Long userId);

    AiFeedbackTaskVO getTask(Long taskId, Long userId);

    AiFeedbackTaskVO getLatestBySubmission(Long submissionId, Long userId);

    void executeTask(Long taskId);
}
