package com.qwerlty.myojbackendquestionservice.mapper;

import com.qwerlty.myojbackendquestionservice.model.AiQuestionPublishRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface AiQuestionPublishMapper {

    @Insert("INSERT INTO ai_question_publish "
            + "(idempotencyKey, sourceTaskId, reviewerId, payloadHash, createTime, updateTime) "
            + "VALUES (#{idempotencyKey}, #{sourceTaskId}, #{reviewerId}, #{payloadHash}, NOW(), NOW()) "
            + "ON DUPLICATE KEY UPDATE idempotencyKey = ai_question_publish.idempotencyKey")
    int claim(@Param("idempotencyKey") String idempotencyKey,
              @Param("sourceTaskId") long sourceTaskId,
              @Param("reviewerId") long reviewerId,
              @Param("payloadHash") String payloadHash);

    @Select("SELECT idempotencyKey, sourceTaskId, reviewerId, payloadHash, questionId "
            + "FROM ai_question_publish WHERE idempotencyKey = #{idempotencyKey} FOR UPDATE")
    AiQuestionPublishRecord findForUpdate(@Param("idempotencyKey") String idempotencyKey);

    @Update("UPDATE ai_question_publish SET questionId = #{questionId}, updateTime = NOW() "
            + "WHERE idempotencyKey = #{idempotencyKey} AND questionId IS NULL")
    int complete(@Param("idempotencyKey") String idempotencyKey,
                 @Param("questionId") long questionId);
}
