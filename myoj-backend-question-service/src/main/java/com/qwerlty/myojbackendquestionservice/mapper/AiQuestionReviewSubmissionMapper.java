package com.qwerlty.myojbackendquestionservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qwerlty.myojbackendquestionservice.model.entity.AiQuestionReviewSubmission;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface AiQuestionReviewSubmissionMapper extends BaseMapper<AiQuestionReviewSubmission> {
    @Select("select * from ai_question_review_submission where id = #{id} for update")
    AiQuestionReviewSubmission selectForUpdate(@Param("id") Long id);

    @Update("update ai_question_review_submission set status = 'WITHDRAWN', updateTime = now(), "
            + "lockVersion = lockVersion + 1 where id = #{id} and userId = #{userId} and status = 'PENDING'")
    int withdraw(@Param("id") Long id, @Param("userId") Long userId);

    @Update("update ai_question_review_submission set qualityReviewTaskId = #{taskId}, updateTime = now(), "
            + "lockVersion = lockVersion + 1 where id = #{id} and status = 'PENDING' and qualityReviewTaskId is null")
    int attachQualityTask(@Param("id") Long id, @Param("taskId") Long taskId);

    @Update("update ai_question_review_submission set status = 'REJECTED', reviewerId = #{reviewerId}, "
            + "reviewReason = #{reason}, reviewTime = now(), updateTime = now(), lockVersion = lockVersion + 1 "
            + "where id = #{id} and status = 'PENDING'")
    int reject(@Param("id") Long id, @Param("reviewerId") Long reviewerId, @Param("reason") String reason);

    @Update("update ai_question_review_submission set status = 'APPROVED', reviewerId = #{reviewerId}, "
            + "publishedQuestionId = #{questionId}, reviewTime = now(), updateTime = now(), lockVersion = lockVersion + 1 "
            + "where id = #{id} and status = 'PENDING'")
    int approve(@Param("id") Long id, @Param("reviewerId") Long reviewerId, @Param("questionId") Long questionId);
}
