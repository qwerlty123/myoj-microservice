package com.qwerlty.myojbackendaiservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qwerlty.myojbackendaiservice.model.entity.AiFeedbackTask;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface AiFeedbackTaskMapper extends BaseMapper<AiFeedbackTask> {

    @Select("select * from ai_feedback_task where requestKey = #{requestKey} limit 1")
    AiFeedbackTask selectByRequestKey(@Param("requestKey") String requestKey);

    @Select("select * from ai_feedback_task where userId = #{userId} and submissionId = #{submissionId} "
            + "order by createTime desc limit 1")
    AiFeedbackTask selectLatest(@Param("userId") Long userId, @Param("submissionId") Long submissionId);

    @Select("<script>select count(*) from ai_feedback_task where userId = #{userId}"
            + "<if test='submissionId != null'> and submissionId = #{submissionId}</if></script>")
    long countHistory(@Param("userId") Long userId, @Param("submissionId") Long submissionId);

    @Select("<script>select * from ai_feedback_task where userId = #{userId}"
            + "<if test='submissionId != null'> and submissionId = #{submissionId}</if>"
            + " order by createTime desc limit #{offset}, #{pageSize}</script>")
    List<AiFeedbackTask> listHistory(@Param("userId") Long userId,
                                     @Param("submissionId") Long submissionId,
                                     @Param("offset") long offset,
                                     @Param("pageSize") int pageSize);

    @Update("update ai_feedback_task set status = 1, attemptCount = attemptCount + 1, "
            + "startedTime = now(), finishedTime = null, errorCode = null, lastError = null, updateTime = now() "
            + "where id = #{id} and status = 0")
    int claimForExecution(@Param("id") Long id);

    @Update("update ai_feedback_task set status = 2, resultJson = #{resultJson}, "
            + "inputTokens = #{inputTokens}, outputTokens = #{outputTokens}, latencyMs = #{latencyMs}, "
            + "finishedTime = now(), errorCode = null, lastError = null, updateTime = now() "
            + "where id = #{id} and status = 1")
    int markSuccess(@Param("id") Long id,
                    @Param("resultJson") String resultJson,
                    @Param("inputTokens") int inputTokens,
                    @Param("outputTokens") int outputTokens,
                    @Param("latencyMs") long latencyMs);

    @Update("update ai_feedback_task set status = 0, startedTime = null, "
            + "errorCode = #{errorCode}, lastError = #{lastError}, updateTime = now() "
            + "where id = #{id} and status = 1")
    int markExecutionRetry(@Param("id") Long id,
                           @Param("errorCode") String errorCode,
                           @Param("lastError") String lastError);

    @Update("update ai_feedback_task set status = #{status}, errorCode = #{errorCode}, "
            + "lastError = #{lastError}, latencyMs = #{latencyMs}, finishedTime = now(), updateTime = now() "
            + "where id = #{id} and status = 1")
    int markExecutionTerminal(@Param("id") Long id,
                              @Param("status") int status,
                              @Param("errorCode") String errorCode,
                              @Param("lastError") String lastError,
                              @Param("latencyMs") long latencyMs);

    @Update("update ai_feedback_task set status = 0, attemptCount = 0, "
            + "startedTime = null, finishedTime = null, resultJson = null, errorCode = null, "
            + "lastError = null, modelName = #{modelName}, updateTime = now() "
            + "where id = #{id} and status in (3, 4)")
    int resetFailedTask(@Param("id") Long id, @Param("modelName") String modelName);

    @Update("update ai_feedback_task set status = #{status}, errorCode = #{errorCode}, "
            + "lastError = #{lastError}, finishedTime = now(), updateTime = now() "
            + "where id = #{id} and status = 0")
    int markPendingTerminal(@Param("id") Long id,
                            @Param("status") int status,
                            @Param("errorCode") String errorCode,
                            @Param("lastError") String lastError);

}
