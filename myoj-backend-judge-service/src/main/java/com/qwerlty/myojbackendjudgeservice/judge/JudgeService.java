package com.qwerlty.myojbackendjudgeservice.judge;


import com.qwerlty.myojbackendmodel.model.entity.QuestionSubmit;

/**
 * 判题服务
 */
public interface JudgeService {

    QuestionSubmit doJudge(Long questionSubmitId);

}
