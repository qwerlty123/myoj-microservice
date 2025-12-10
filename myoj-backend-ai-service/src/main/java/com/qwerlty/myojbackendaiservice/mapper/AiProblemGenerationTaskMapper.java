package com.qwerlty.myojbackendaiservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qwerlty.myojbackendaiservice.model.entity.AiProblemGenerationTask;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Date;

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

    @Select({"<script>",
            "select count(*) from ai_problem_generation_task where userId = #{userId}",
            "<if test='type != null'> and mode = #{type}</if>",
            "</script>"})
    long countHistoryByType(@Param("userId") Long userId, @Param("type") String type);

    @Select({"<script>",
            "select id, userId, mode, lane, sourceTaskId, submissionId, traceId, status, stage, progress, resultJson, ",
            "modelName, inputTokens, outputTokens, modelCallCount, estimatedCostMicros, quotaCost, quotaDate, quotaStatus, ",
            "latencyMs, failureStage, nextAttemptTime, degraded, errorCode, lastError, createTime, updateTime ",
            "from ai_problem_generation_task where userId = #{userId}",
            "<if test='type != null'> and mode = #{type}</if>",
            "order by createTime desc limit #{offset}, #{pageSize}",
            "</script>"})
    List<AiProblemGenerationTask> listHistoryByType(@Param("userId") Long userId,
                                                    @Param("type") String type,
                                                    @Param("offset") long offset,
                                                    @Param("pageSize") int pageSize);

    @Select({"<script>",
            "select count(*) from ai_problem_generation_task where 1 = 1",
            "<if test='type != null'> and mode = #{type}</if>",
            "</script>"})
    long countAllHistoryByType(@Param("type") String type);

    @Select({"<script>",
            "select id, userId, mode, lane, sourceTaskId, submissionId, traceId, status, stage, progress, resultJson, ",
            "modelName, inputTokens, outputTokens, modelCallCount, estimatedCostMicros, quotaCost, quotaDate, quotaStatus, ",
            "latencyMs, failureStage, nextAttemptTime, degraded, errorCode, lastError, createTime, updateTime ",
            "from ai_problem_generation_task where 1 = 1",
            "<if test='type != null'> and mode = #{type}</if>",
            "order by createTime desc limit #{offset}, #{pageSize}",
            "</script>"})
    List<AiProblemGenerationTask> listAllHistoryByType(@Param("type") String type,
                                                       @Param("offset") long offset,
                                                       @Param("pageSize") int pageSize);

    @Select("select id, userId, requestKey, mode, lane, quotaDate from ai_problem_generation_task "
            + "where status = 0 and cancelRequested = 0 "
            + "and (nextAttemptTime is null or nextAttemptTime <= now()) "
            + "order by createTime asc limit #{limit}")
    List<AiProblemGenerationTask> listPending(@Param("limit") int limit);

    @Update("update ai_problem_generation_task set nextAttemptTime = #{nextAttemptTime}, updateTime = now(), "
            + "version = version + 1 where id = #{id} and status = 0")
    int deferPending(@Param("id") Long id, @Param("nextAttemptTime") Date nextAttemptTime);

    @Update("update ai_problem_generation_task set status = 1, stage = 'QUEUED', progress = 1, "
            + "attemptCount = attemptCount + 1, startedTime = now(), finishedTime = null, "
            + "nextAttemptTime = null, errorCode = null, lastError = null, updateTime = now(), version = version + 1 "
            + "where id = #{id} and status = 0 and cancelRequested = 0")
    int claimForExecution(@Param("id") Long id);

    @Update("update ai_problem_generation_task set stage = #{stage}, progress = #{progress}, "
            + "updateTime = now(), version = version + 1 where id = #{id} and status = 1")
    int updateStage(@Param("id") Long id,
                    @Param("stage") String stage,
                    @Param("progress") int progress);

    @Update("update ai_problem_generation_task set workflowStateJson = #{workflowStateJson}, "
            + "stage = #{stage}, progress = #{progress}, updateTime = now(), version = version + 1 "
            + "where id = #{id} and status = 1")
    int updateCheckpoint(@Param("id") Long id,
                         @Param("workflowStateJson") String workflowStateJson,
                         @Param("stage") String stage,
                         @Param("progress") int progress);

    @Update("update ai_problem_generation_task set workflowStateJson = null, "
            + "updateTime = now(), version = version + 1 where id = #{id}")
    int clearCheckpoint(@Param("id") Long id);

    @Update("update ai_problem_generation_task set promptVersion = #{promptVersion}, workflowStateJson = null, "
            + "updateTime = now(), version = version + 1 where id = #{id} and status = 1")
    int replacePromptVersionAndClearCheckpoint(@Param("id") Long id,
                                               @Param("promptVersion") String promptVersion);

    @Update("update ai_problem_generation_task set status = 2, stage = 'COMPLETED', progress = 100, "
            + "resultJson = #{resultJson}, validationJson = null, workflowStateJson = null, latencyMs = #{latencyMs}, "
            + "finishedTime = now(), errorCode = null, lastError = null, updateTime = now(), version = version + 1 "
            + "where id = #{id} and status = 1 and cancelRequested = 0")
    int markReviewRequired(@Param("id") Long id,
                           @Param("resultJson") String resultJson,
                           @Param("latencyMs") long latencyMs);

    @Update("update ai_problem_generation_task set status = 0, stage = 'QUEUED', progress = 0, "
            + "startedTime = null, errorCode = #{errorCode}, lastError = #{lastError}, "
            + "updateTime = now(), version = version + 1 where id = #{id} and status = 1 and cancelRequested = 0")
    int markRetry(@Param("id") Long id,
                  @Param("errorCode") String errorCode,
                  @Param("lastError") String lastError);

    @Update("update ai_problem_generation_task set status = 0, stage = 'QUEUED', progress = 0, "
            + "startedTime = null, nextAttemptTime = now(), errorCode = 'SHUTDOWN_RECOVERED', "
            + "lastError = '服务停机，任务将从最近断点恢复', failureStage = #{failureStage}, "
            + "updateTime = now(), version = version + 1 where id = #{id} and status = 1")
    int markShutdownRecovery(@Param("id") Long id, @Param("failureStage") String failureStage);

    @Update("update ai_problem_generation_task set status = 0, stage = 'QUEUED', progress = 0, "
            + "startedTime = null, nextAttemptTime = #{nextAttemptTime}, errorCode = #{errorCode}, lastError = #{lastError}, "
            + "failureStage = #{failureStage}, "
            + "updateTime = now(), version = version + 1 where id = #{id} and status = 1 and cancelRequested = 0")
    int markRetryDelayed(@Param("id") Long id,
                         @Param("errorCode") String errorCode,
                         @Param("lastError") String lastError,
                         @Param("failureStage") String failureStage,
                         @Param("nextAttemptTime") Date nextAttemptTime);

    @Update("update ai_problem_generation_task set status = 0, stage = 'QUEUED', progress = 0, "
            + "attemptCount = greatest(0, attemptCount - 1), startedTime = null, "
            + "nextAttemptTime = #{nextAttemptTime}, errorCode = 'CAPACITY_WAIT', "
            + "lastError = '正在等待模型或沙箱执行许可', failureStage = #{failureStage}, "
            + "updateTime = now(), version = version + 1 where id = #{id} and status = 1 and cancelRequested = 0")
    int markCapacityDeferred(@Param("id") Long id,
                             @Param("failureStage") String failureStage,
                             @Param("nextAttemptTime") Date nextAttemptTime);

    @Update("update ai_problem_generation_task set status = #{status}, errorCode = #{errorCode}, "
            + "lastError = #{lastError}, failureStage = #{failureStage}, latencyMs = #{latencyMs}, finishedTime = now(), "
            + "updateTime = now(), version = version + 1 where id = #{id} and status = 1")
    int markTerminal(@Param("id") Long id,
                     @Param("status") int status,
                     @Param("errorCode") String errorCode,
                     @Param("lastError") String lastError,
                     @Param("failureStage") String failureStage,
                     @Param("latencyMs") long latencyMs);

    @Update("update ai_problem_generation_task set status = 0, stage = 'QUEUED', progress = 0, "
            + "resultJson = null, validationJson = null, startedTime = null, finishedTime = null, "
            + "cancelRequested = 0, errorCode = null, lastError = null, promptVersion = #{promptVersion}, "
            + "workflowStateJson = case when #{clearCheckpoint} = 1 then null else workflowStateJson end, "
            + "updateTime = now(), version = version + 1 "
            + "where id = #{id} and userId = #{userId} and status in (3, 4)")
    int resetForRetry(@Param("id") Long id,
                      @Param("userId") Long userId,
                      @Param("promptVersion") String promptVersion,
                      @Param("clearCheckpoint") int clearCheckpoint);

    @Update("update ai_problem_generation_task set status = 5, finishedTime = now(), "
            + "errorCode = null, lastError = null, updateTime = now(), version = version + 1 "
            + "where id = #{id} and userId = #{userId} and status = 0")
    int cancelPending(@Param("id") Long id, @Param("userId") Long userId);

    @Update("update ai_problem_generation_task set cancelRequested = 1, updateTime = now(), version = version + 1 "
            + "where id = #{id} and userId = #{userId} and status = 1")
    int requestRunningCancellation(@Param("id") Long id, @Param("userId") Long userId);

    @Update("update ai_problem_generation_task set quotaStatus = #{toStatus}, updateTime = now(), version = version + 1 "
            + "where id = #{id} and quotaStatus = #{fromStatus}")
    int updateQuotaStatus(@Param("id") Long id,
                          @Param("fromStatus") String fromStatus,
                          @Param("toStatus") String toStatus);

    @Select("select id, userId, requestKey, mode, lane, status, startedTime, quotaDate, errorCode "
            + "from ai_problem_generation_task where quotaStatus = 'RESERVED' and status in (2,3,4,5) "
            + "order by updateTime asc limit #{limit}")
    List<AiProblemGenerationTask> listUnsettledTerminalTasks(@Param("limit") int limit);

    @Update("update ai_problem_generation_task set inputTokens = inputTokens + #{inputTokens}, "
            + "outputTokens = outputTokens + #{outputTokens}, modelCallCount = modelCallCount + 1, "
            + "estimatedCostMicros = estimatedCostMicros + #{costMicros}, "
            + "modelName = coalesce(#{modelName}, modelName), updateTime = now(), version = version + 1 "
            + "where id = #{id}")
    int addModelUsage(@Param("id") Long id,
                      @Param("inputTokens") int inputTokens,
                      @Param("outputTokens") int outputTokens,
                      @Param("costMicros") long costMicros,
                      @Param("modelName") String modelName);

    @Update("update ai_problem_generation_task set degraded = 1, updateTime = now(), version = version + 1 where id = #{id}")
    int markDegraded(@Param("id") Long id);

    @Update("update ai_problem_generation_task set requestJson = json_object('purged', true), resultJson = null, "
            + "validationJson = null, workflowStateJson = null, payloadPurgedTime = now(), "
            + "updateTime = now(), version = version + 1 where id = #{id} and payloadPurgedTime is null")
    int purgePayload(@Param("id") Long id);

    @Select("select id from ai_problem_generation_task where payloadPurgedTime is null and "
            + "((status = 2 and finishedTime < date_sub(now(), interval 30 day)) or "
            + "(status in (3,4,5) and finishedTime < date_sub(now(), interval 7 day))) limit #{limit}")
    List<AiProblemGenerationTask> listPayloadsToPurge(@Param("limit") int limit);

    @Select("select count(*) from ai_problem_generation_task where status in (0,1) and lane = #{lane}")
    long countInflightByLane(@Param("lane") String lane);

    @Select("select coalesce(max(timestampdiff(second, createTime, now())), 0) from ai_problem_generation_task where status = 0")
    long oldestPendingSeconds();

    @Select("select coalesce(sum(estimatedCostMicros), 0) from ai_problem_generation_task "
            + "where lane = #{lane} and createTime >= #{start} and createTime < #{end}")
    Long sumEstimatedCost(@Param("lane") String lane,
                          @Param("start") Date start,
                          @Param("end") Date end);

    @Select("select count(*) from ai_problem_generation_task where status in (0, 1)")
    long countActiveForAdmission();

    @Select("select count(*) from ai_problem_generation_task where status in (0, 1) and lane = #{lane}")
    long countActiveForAdmissionByLane(@Param("lane") String lane);

    @Select("select count(*) from ai_problem_generation_task where status = 0 and userId = #{userId}")
    long countPendingForAdmission(@Param("userId") Long userId);

    @Select("select id from ai_problem_generation_task where status = 1 and userId = #{userId} "
            + "order by startedTime asc limit 1")
    Long selectRunningTaskForAdmission(@Param("userId") Long userId);

    @Select("select coalesce(sum(quotaCost), 0) from ai_problem_generation_task "
            + "where userId = #{userId} and quotaDate = #{quotaDate} and quotaStatus != 'REFUNDED'")
    Long sumQuotaForAdmission(@Param("userId") Long userId,
                              @Param("quotaDate") java.sql.Date quotaDate);
}
