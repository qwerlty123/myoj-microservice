package com.qwerlty.myojbackendquestionservice.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qwerlty.myojbackendmodel.model.dto.question.QuestionAddRequest;
import com.qwerlty.myojbackendquestionservice.model.dto.AiQuestionSubmissionRequest;
import com.qwerlty.myojbackendquestionservice.model.vo.AiQuestionReviewSubmissionVO;

public interface AiQuestionReviewService {
    AiQuestionReviewSubmissionVO submit(Long userId, AiQuestionSubmissionRequest request);
    Page<AiQuestionReviewSubmissionVO> listMine(Long userId, int current, int pageSize);
    Page<AiQuestionReviewSubmissionVO> listAdmin(String status, int current, int pageSize);
    AiQuestionReviewSubmissionVO get(Long id, Long actorId, boolean admin);
    AiQuestionReviewSubmissionVO withdraw(Long id, Long userId);
    AiQuestionReviewSubmissionVO reject(Long id, Long reviewerId, String reason);
    Long approve(Long id, Long reviewerId);
    AiQuestionReviewSubmissionVO startQualityReview(Long id, Long reviewerId);
    QuestionAddRequest authoritativeSnapshot(Long id);
}
