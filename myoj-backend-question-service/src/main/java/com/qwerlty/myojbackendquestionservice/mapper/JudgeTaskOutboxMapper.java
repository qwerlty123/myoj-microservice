package com.qwerlty.myojbackendquestionservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qwerlty.myojbackendquestionservice.model.entity.JudgeTaskOutbox;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Date;
import java.util.List;

public interface JudgeTaskOutboxMapper extends BaseMapper<JudgeTaskOutbox> {

    @Select("SELECT * FROM judge_task_outbox " +
            "WHERE status = 0 AND nextRetryTime <= NOW() " +
            "ORDER BY id ASC LIMIT #{limit}")
    List<JudgeTaskOutbox> listDispatchCandidates(@Param("limit") int limit);

    @Update("UPDATE judge_task_outbox SET status = 3, updateTime = NOW() " +
            "WHERE id = #{id} AND status = 0 AND nextRetryTime <= NOW()")
    int claimForDispatch(@Param("id") Long id);

    @Update("UPDATE judge_task_outbox SET status = 1, updateTime = NOW(), lastError = NULL " +
            "WHERE id = #{id} AND status = 3")
    int markSent(@Param("id") Long id);

    @Update("UPDATE judge_task_outbox SET status = #{status}, retryCount = #{retryCount}, " +
            "nextRetryTime = #{nextRetryTime}, lastError = #{lastError}, updateTime = NOW() " +
            "WHERE id = #{id}")
    int markRetryOrStop(@Param("id") Long id,
                        @Param("status") Integer status,
                        @Param("retryCount") Integer retryCount,
                        @Param("nextRetryTime") Date nextRetryTime,
                        @Param("lastError") String lastError);

    @Select("SELECT COUNT(1) FROM judge_task_outbox WHERE questionSubmitId = #{questionSubmitId} AND status IN (0, 3)")
    int countActiveDispatchBySubmitId(@Param("questionSubmitId") Long questionSubmitId);

    @Update("UPDATE judge_task_outbox SET status = 0, updateTime = NOW() " +
            "WHERE status = 3 AND updateTime < #{deadline}")
    int releaseStaleDispatching(@Param("deadline") Date deadline);
}

