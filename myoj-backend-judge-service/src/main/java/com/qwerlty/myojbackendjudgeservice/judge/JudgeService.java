package com.qwerlty.myojbackendjudgeservice.judge;


import com.qwerlty.myojbackendmodel.model.entity.QuestionSubmit;
import com.qwerlty.myojbackendmodel.model.dto.judge.JudgeTaskMessage;

/**
 * 判题服务
 */
public interface JudgeService {

    QuestionSubmit doJudge(Long questionSubmitId);

    QuestionSubmit doJudge(JudgeTaskMessage message);

}
