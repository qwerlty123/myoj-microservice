package com.qwerlty.myojbackendjudgeservice.judge.codesandbox;

import com.qwerlty.myojbackendcommon.common.ErrorCode;
import com.qwerlty.myojbackendcommon.exception.BusinessException;

/**
 * 代表重试也不会自行恢复的远程沙箱配置错误。
 */
public class SandboxConfigurationException extends BusinessException {

    public SandboxConfigurationException(String message) {
        super(ErrorCode.API_REQUEST_ERROR, message);
    }
}
