package com.qwerlty.myojbackendaiservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qwerlty.myojbackendaiservice.model.entity.AiFeedbackTask;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Date;
import java.util.List;

public interface AiFeedbackTaskMapper extends BaseMapper<AiFeedbackTask> {

    @Select("select * from ai_feedback_task where requestKey = #{requestKey} limit 1")
    AiFeedbackTask selectByRequestKey(@Param("requestKey") String requestKey);

    @Select("select * from ai_feedback_task where userId = #{userId} and submissionId = #{submissionId} "
            + "order by createTime desc limit 1")
    AiFeedbackTask selectLatest(@Param("userId") Long userId, @Param("submissionId") Long submissionId);

    @Select("select * from ai_feedback_task where status = 0 and nextRetryTime <= now() "
            + "order by nextRetryTime asc limit #{limit}")
    List<AiFeedbackTask> listDispatchCandidates(@Param("limit") int limit);

    @Update("update ai_feedback_task set status = 1, updateTime = now() where id = #{id} and status = 0")
    int claimForDispatch(@Param("id") Long id);

    @Update("update ai_feedback_task set status = 2, lastError = null, errorCode = null, updateTime = now() "
            + "where id = #{id} and status = 1")
    int markQueued(@Param("id") Long id);

    @Update("update ai_feedback_task set status = #{status}, dispatchRetryCount = #{retryCount}, "
            + "nextRetryTime = #{nextRetryTime}, errorCode = #{errorCode}, lastError = #{lastError}, "
            + "updateTime = now() where id = #{id} and status = 1")
    int markDispatchFailure(@Param("id") Long id,
                            @Param("status") int status,
                            @Param("retryCount") int retryCount,
                            @Param("nextRetryTime") Date nextRetryTime,
                            @Param("errorCode") String errorCode,
                            @Param("lastError") String lastError);

    @Update("update ai_feedback_task set status = 3, executeRetryCount = executeRetryCount + 1, "
            + "updateTime = now() where id = #{id} and status = 2")
    int claimForExecution(@Param("id") Long id);

    @Update("update ai_feedback_task set status = 4, resultJson = #{resultJson}, citationsJson = #{citationsJson}, "
            + "inputTokens = #{inputTokens}, outputTokens = #{outputTokens}, latencyMs = #{latencyMs}, "
            + "errorCode = null, lastError = null, updateTime = now() where id = #{id} and status = 3")
    int markSuccess(@Param("id") Long id,
                    @Param("resultJson") String resultJson,
                    @Param("citationsJson") String citationsJson,
                    @Param("inputTokens") int inputTokens,
                    @Param("outputTokens") int outputTokens,
                    @Param("latencyMs") long latencyMs);

    @Update("update ai_feedback_task set status = 0, nextRetryTime = #{nextRetryTime}, "
            + "errorCode = #{errorCode}, lastError = #{lastError}, updateTime = now() "
            + "where id = #{id} and status = 3")
    int markExecutionRetry(@Param("id") Long id,
                           @Param("nextRetryTime") Date nextRetryTime,
                           @Param("errorCode") String errorCode,
                           @Param("lastError") String lastError);

    @Update("update ai_feedback_task set status = #{status}, errorCode = #{errorCode}, "
            + "lastError = #{lastError}, latencyMs = #{latencyMs}, updateTime = now() "
            + "where id = #{id} and status = 3")
    int markExecutionTerminal(@Param("id") Long id,
                              @Param("status") int status,
                              @Param("errorCode") String errorCode,
                              @Param("lastError") String lastError,
                              @Param("latencyMs") long latencyMs);

    @Update("update ai_feedback_task set status = 0, dispatchRetryCount = 0, executeRetryCount = 0, "
            + "nextRetryTime = now(), resultJson = null, citationsJson = null, errorCode = null, "
            + "lastError = null, modelName = #{modelName}, updateTime = now() "
            + "where id = #{id} and status in (5, 6)")
    int resetFailedTask(@Param("id") Long id, @Param("modelName") String modelName);

    @Update("update ai_feedback_task set status = 0, nextRetryTime = now(), updateTime = now() "
            + "where status = 1 and updateTime < #{staleBefore}")
    int releaseStaleDispatching(@Param("staleBefore") Date staleBefore);

    @Select("select * from ai_feedback_task where status = 3 and updateTime < #{staleBefore} "
            + "order by updateTime asc limit #{limit}")
    List<AiFeedbackTask> listStaleRunning(@Param("staleBefore") Date staleBefore, @Param("limit") int limit);
}
