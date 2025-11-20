package com.qwerlty.myojbackendaiservice.service;

import com.qwerlty.myojbackendaiservice.model.vo.AiFeedbackTaskVO;
import com.qwerlty.myojbackendaiservice.model.vo.AiFeedbackPageVO;

public interface AiFeedbackService {
    AiFeedbackTaskVO createTask(Long submissionId, Long userId);

    AiFeedbackTaskVO getTask(Long taskId, Long userId);

    AiFeedbackTaskVO getLatestBySubmission(Long submissionId, Long userId);

    AiFeedbackPageVO getHistory(Long userId, Long submissionId, int current, int pageSize);

    void executeTask(Long taskId);
}
