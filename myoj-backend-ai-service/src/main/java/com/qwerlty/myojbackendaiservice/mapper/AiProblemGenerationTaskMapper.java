package com.qwerlty.myojbackendaiservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qwerlty.myojbackendaiservice.model.entity.AiProblemGenerationTask;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface AiProblemGenerationTaskMapper extends BaseMapper<AiProblemGenerationTask> {

    @Select("select * from ai_problem_generation_task where requestKey = #{requestKey} limit 1")
    AiProblemGenerationTask selectByRequestKey(@Param("requestKey") String requestKey);

    @Select("select count(*) from ai_problem_generation_task where userId = #{userId}")
    long countHistory(@Param("userId") Long userId);

    @Select("select id, userId, mode, status, stage, progress, errorCode, lastError, createTime, updateTime "
            + "from ai_problem_generation_task where userId = #{userId} "
            + "order by createTime desc limit #{offset}, #{pageSize}")
    List<AiProblemGenerationTask> listHistory(@Param("userId") Long userId,
                                              @Param("offset") long offset,
                                              @Param("pageSize") int pageSize);

    @Select("select id from ai_problem_generation_task where status = 0 and cancelRequested = 0 "
            + "order by createTime asc limit #{limit}")
    List<AiProblemGenerationTask> listPending(@Param("limit") int limit);

    @Update("update ai_problem_generation_task set status = 1, stage = 'GENERATING_SPEC', progress = 10, "
            + "attemptCount = attemptCount + 1, startedTime = now(), finishedTime = null, "
            + "errorCode = null, lastError = null, updateTime = now(), version = version + 1 "
            + "where id = #{id} and status = 0 and cancelRequested = 0")
    int claimForExecution(@Param("id") Long id);

    @Update("update ai_problem_generation_task set stage = #{stage}, progress = #{progress}, "
            + "updateTime = now(), version = version + 1 where id = #{id} and status = 1")
    int updateStage(@Param("id") Long id,
                    @Param("stage") String stage,
                    @Param("progress") int progress);

    @Update("update ai_problem_generation_task set status = 2, stage = 'COMPLETED', progress = 100, "
            + "resultJson = #{resultJson}, validationJson = #{validationJson}, latencyMs = #{latencyMs}, "
            + "finishedTime = now(), errorCode = null, lastError = null, updateTime = now(), version = version + 1 "
            + "where id = #{id} and status = 1 and cancelRequested = 0")
    int markReviewRequired(@Param("id") Long id,
                           @Param("resultJson") String resultJson,
                           @Param("validationJson") String validationJson,
                           @Param("latencyMs") long latencyMs);

    @Update("update ai_problem_generation_task set status = 0, stage = 'QUEUED', progress = 0, "
            + "startedTime = null, errorCode = #{errorCode}, lastError = #{lastError}, "
            + "updateTime = now(), version = version + 1 where id = #{id} and status = 1 and cancelRequested = 0")
    int markRetry(@Param("id") Long id,
                  @Param("errorCode") String errorCode,
                  @Param("lastError") String lastError);

    @Update("update ai_problem_generation_task set status = #{status}, errorCode = #{errorCode}, "
            + "lastError = #{lastError}, latencyMs = #{latencyMs}, finishedTime = now(), "
            + "updateTime = now(), version = version + 1 where id = #{id} and status = 1")
    int markTerminal(@Param("id") Long id,
                     @Param("status") int status,
                     @Param("errorCode") String errorCode,
                     @Param("lastError") String lastError,
                     @Param("latencyMs") long latencyMs);

    @Update("update ai_problem_generation_task set status = 0, stage = 'QUEUED', progress = 0, "
            + "resultJson = null, validationJson = null, startedTime = null, finishedTime = null, "
            + "cancelRequested = 0, errorCode = null, lastError = null, updateTime = now(), version = version + 1 "
            + "where id = #{id} and userId = #{userId} and status in (3, 4)")
    int resetForRetry(@Param("id") Long id, @Param("userId") Long userId);

    @Update("update ai_problem_generation_task set status = 5, finishedTime = now(), "
            + "errorCode = null, lastError = null, updateTime = now(), version = version + 1 "
            + "where id = #{id} and userId = #{userId} and status = 0")
    int cancelPending(@Param("id") Long id, @Param("userId") Long userId);

    @Update("update ai_problem_generation_task set cancelRequested = 1, updateTime = now(), version = version + 1 "
            + "where id = #{id} and userId = #{userId} and status = 1")
    int requestRunningCancellation(@Param("id") Long id, @Param("userId") Long userId);
}
