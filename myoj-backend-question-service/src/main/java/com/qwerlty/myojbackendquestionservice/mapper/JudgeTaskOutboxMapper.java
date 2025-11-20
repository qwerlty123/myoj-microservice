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

    @Update("UPDATE judge_task_outbox SET status = 3, lockToken = #{lockToken}, " +
            "leaseUntil = TIMESTAMPADD(MICROSECOND, #{leaseMs} * 1000, NOW(3)), updateTime = NOW() " +
            "WHERE id = #{id} AND status = 0 AND nextRetryTime <= NOW()")
    int claimForDispatch(@Param("id") Long id,
                         @Param("lockToken") String lockToken,
                         @Param("leaseMs") Long leaseMs);

    @Update("UPDATE judge_task_outbox SET status = 1, updateTime = NOW(), lastError = NULL, " +
            "lockToken = NULL, leaseUntil = NULL WHERE id = #{id} AND status = 3 AND lockToken = #{lockToken}")
    int markSent(@Param("id") Long id, @Param("lockToken") String lockToken);

    @Update("UPDATE judge_task_outbox SET status = #{status}, retryCount = #{retryCount}, " +
            "nextRetryTime = #{nextRetryTime}, lastError = #{lastError}, updateTime = NOW(), " +
            "lockToken = NULL, leaseUntil = NULL " +
            "WHERE id = #{id} AND status = 3 AND lockToken = #{lockToken}")
    int markRetryOrDead(@Param("id") Long id,
                        @Param("lockToken") String lockToken,
                        @Param("status") Integer status,
                        @Param("retryCount") Integer retryCount,
                        @Param("nextRetryTime") Date nextRetryTime,
                        @Param("lastError") String lastError);

    @Update("UPDATE judge_task_outbox SET status = 0, lockToken = NULL, leaseUntil = NULL, updateTime = NOW() " +
            "WHERE status = 3 AND leaseUntil IS NOT NULL AND leaseUntil <= NOW(3)")
    int releaseExpiredLeases();

    @Select("SELECT COUNT(1) FROM judge_task_outbox WHERE status = #{status}")
    long countByStatus(@Param("status") int status);
}
