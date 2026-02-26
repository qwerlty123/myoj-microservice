package com.qwerlty.myojbackendjudgeservice.judge.strategy;


import com.qwerlty.myojbackendmodel.model.codesandbox.JudgeInfo;

/**
 * 判题策略
 */

public interface JudgeStrategy {

    JudgeInfo doJudge(JudgeContext judgeContext);

}
