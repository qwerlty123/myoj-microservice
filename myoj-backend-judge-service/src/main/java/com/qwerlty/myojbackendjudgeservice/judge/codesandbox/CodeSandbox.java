package com.qwerlty.myojbackendjudgeservice.judge.codesandbox;


import com.qwerlty.myojbackendmodel.model.codesandbox.ExecuteCodeRequest;
import com.qwerlty.myojbackendmodel.model.codesandbox.ExecuteCodeResponse;

public interface CodeSandbox {

    ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest);

}
