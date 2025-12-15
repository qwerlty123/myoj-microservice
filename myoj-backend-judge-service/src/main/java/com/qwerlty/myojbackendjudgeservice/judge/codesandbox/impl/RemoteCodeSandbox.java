package com.qwerlty.myojbackendjudgeservice.judge.codesandbox.impl;

import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONUtil;
import com.qwerlty.myojbackendcommon.common.ErrorCode;
import com.qwerlty.myojbackendcommon.exception.BusinessException;
import com.qwerlty.myojbackendcommon.utils.ApiSignUtil;
import com.qwerlty.myojbackendjudgeservice.judge.codesandbox.CodeSandbox;
import com.qwerlty.myojbackendmodel.model.codesandbox.ExecuteCodeRequest;
import com.qwerlty.myojbackendmodel.model.codesandbox.ExecuteCodeResponse;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 远程代码沙箱，通过 API 签名认证与沙箱服务通信
 */
@Component
public class RemoteCodeSandbox implements CodeSandbox {

    public static final String HEADER_TIMESTAMP = "X-Timestamp";
    public static final String HEADER_SIGNATURE = "X-Signature";

    @Value("${codesandbox.url:http://localhost:8090/executeCode}")
    private String sandboxUrl;

    @Value("${codesandbox.secretKey:}")
    private String secretKey;

    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {
        String json = JSONUtil.toJsonStr(executeCodeRequest);
        long timestamp = System.currentTimeMillis();
        String signature = ApiSignUtil.sign(secretKey, timestamp, json);

        String responseStr = HttpUtil.createPost(sandboxUrl)
                .header(HEADER_TIMESTAMP, String.valueOf(timestamp))
                .header(HEADER_SIGNATURE, signature)
                .body(json)
                .execute()
                .body();
        if (StringUtils.isBlank(responseStr)) {
            throw new BusinessException(ErrorCode.API_REQUEST_ERROR, "executeCode remoteSandbox error, message = " + responseStr);
        }
        return JSONUtil.toBean(responseStr, ExecuteCodeResponse.class);
    }
}
