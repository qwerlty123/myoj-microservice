package com.qwerlty.myojbackendquestionservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qwerlty.myojbackendmodel.model.entity.QuestionSubmit;
import com.qwerlty.myojbackendmodel.model.vo.UserLeaderboardVO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Date;
import java.util.List;


/**
* @author 17871
* @description 针对表【question_submit(题目提交)】的数据库操作Mapper
* @createDate 2026-02-20 22:25:35
* @Entity com.myoj.model.entity.QuestionSubmit
*/
public interface QuestionSubmitMapper extends BaseMapper<QuestionSubmit> {
    @Select("SELECT " +
            "u.id as userId, " +
            "u.userName, " +
            "u.userAvatar, " +
            "COUNT(DISTINCT CASE WHEN qs.status = 2 THEN qs.questionId END) AS solvedCount, " +
            "COUNT(qs.id) as submitCount " +
            "FROM question_submit qs " +
            "JOIN `user` u ON qs.userId = u.id " +
            "WHERE qs.isDelete = 0 AND u.isDelete = 0 " +
            "GROUP BY u.id " +
            "ORDER BY solvedCount DESC, submitCount ASC " +
            "LIMIT #{limit}")
    List<UserLeaderboardVO> getLeaderBoard(@Param("limit") int i);

    @Select("SELECT count(DISTINCT questionId) FROM question_submit WHERE userId = #{userId} AND status = 2 AND isDelete = 0")
    Integer getSolvedCount(@Param("userId")long userId);

    @Update("UPDATE question_submit SET status = 1, updateTime = NOW() " +
            "WHERE id = #{id} AND status = 0 AND isDelete = 0")
    int claimForJudge(@Param("id") Long id);

    @Update("UPDATE question_submit SET status = #{status}, judgeInfo = #{judgeInfo}, lastError = #{lastError}, updateTime = NOW() " +
            "WHERE id = #{id} AND status = 1 AND isDelete = 0")
    int finishFromRunning(@Param("id") Long id,
                          @Param("status") Integer status,
                          @Param("judgeInfo") String judgeInfo,
                          @Param("lastError") String lastError);

    @Select("SELECT * FROM question_submit WHERE status = 1 AND isDelete = 0 AND updateTime < #{deadline} ORDER BY updateTime ASC LIMIT #{limit}")
    List<QuestionSubmit> listTimeoutRunning(@Param("deadline") Date deadline, @Param("limit") int limit);

    @Select("SELECT * FROM question_submit WHERE status = 0 AND isDelete = 0 AND createTime < #{deadline} ORDER BY createTime ASC LIMIT #{limit}")
    List<QuestionSubmit> listStuckWaiting(@Param("deadline") Date deadline, @Param("limit") int limit);

    @Update("UPDATE question_submit SET status = 0, retryCount = retryCount + 1, nextRetryTime = #{nextRetryTime}, " +
            "lastError = #{lastError}, updateTime = NOW() WHERE id = #{id} AND status = 1 AND isDelete = 0")
    int retryRunningAsWaiting(@Param("id") Long id,
                              @Param("nextRetryTime") Date nextRetryTime,
                              @Param("lastError") String lastError);

    @Update("UPDATE question_submit SET status = 3, judgeInfo = #{judgeInfo}, lastError = #{lastError}, updateTime = NOW() " +
            "WHERE id = #{id} AND status IN (0, 1) AND isDelete = 0")
    int markFailedIfUnfinished(@Param("id") Long id,
                               @Param("judgeInfo") String judgeInfo,
                               @Param("lastError") String lastError);
}




