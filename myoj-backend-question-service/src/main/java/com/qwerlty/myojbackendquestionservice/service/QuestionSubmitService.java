package com.qwerlty.myojbackendquestionservice.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.qwerlty.myojbackendmodel.model.dto.questionsubmit.QuestionSubmitAddRequest;
import com.qwerlty.myojbackendmodel.model.dto.questionsubmit.QuestionSubmitQueryRequest;
import com.qwerlty.myojbackendmodel.model.entity.Question;
import com.qwerlty.myojbackendmodel.model.entity.QuestionSubmit;
import com.qwerlty.myojbackendmodel.model.entity.User;
import com.qwerlty.myojbackendmodel.model.vo.MyQuestionSubmitVO;
import com.qwerlty.myojbackendmodel.model.vo.QuestionSubmitVO;
import com.qwerlty.myojbackendmodel.model.vo.UserLeaderboardVO;
import com.qwerlty.myojbackendmodel.model.vo.UserStatsVO;

import java.util.List;


/**
* @author 李天宇
* @description 针对表【question_submit(题目提交)】的数据库操作Service
* @createDate 2026-02-20 22:25:35
*/
public interface QuestionSubmitService extends IService<QuestionSubmit> {
    long doQuestionSubmit(QuestionSubmitAddRequest questionSubmitAddRequest, User loginUser);

    /**
     * 获取查询条件
     *
     * @param questionSubmitQueryRequest
     * @return
     */
    QueryWrapper<QuestionSubmit> getQueryWrapper(QuestionSubmitQueryRequest questionSubmitQueryRequest);


    /**
     * 获取题目封装
     *
     * @param questionSubmit
     * @param loginUser
     * @return
     */
    QuestionSubmitVO getQuestionSubmitVO(QuestionSubmit questionSubmit, User loginUser, User user, Question question);

    /**
     * 分页获取题目封装
     *
     * @param questionPage
     * @param loginUser
     * @return
     */
    Page<QuestionSubmitVO> getQuestionSubmitVOPage(Page<QuestionSubmit> questionPage, User loginUser);

    Page<MyQuestionSubmitVO> getMyQuestionSubmitVOPage(Page<QuestionSubmit> questionSubmitPage, User loginUser);

    UserStatsVO getUserStats(Long id);

    List<UserLeaderboardVO> getLeaderboard();
}
