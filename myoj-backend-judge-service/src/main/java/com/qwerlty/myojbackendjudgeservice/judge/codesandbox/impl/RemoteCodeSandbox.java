package com.qwerlty.myojbackendjudgeservice.judge.codesandbox.impl;

import cn.hutool.http.HttpResponse;
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

    @Value("${codesandbox.timeoutMillis:120000}")
    private int timeoutMillis;

    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {
        String json = JSONUtil.toJsonStr(executeCodeRequest);
        long timestamp = System.currentTimeMillis();
        String signature = ApiSignUtil.sign(secretKey, timestamp, json);

        try (HttpResponse response = HttpUtil.createPost(sandboxUrl)
                .header(HEADER_TIMESTAMP, String.valueOf(timestamp))
                .header(HEADER_SIGNATURE, signature)
                .header("Content-Type", "application/json; charset=UTF-8")
                .body(json)
                .timeout(timeoutMillis)
                .execute()) {
            return parseResponse(response.getStatus(), response.body());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw new BusinessException(ErrorCode.API_REQUEST_ERROR, "远程代码沙箱不可用: " + message);
        }
    }

    ExecuteCodeResponse parseResponse(int httpStatus, String responseStr) {
        if (httpStatus < 200 || httpStatus >= 300) {
            throw new BusinessException(ErrorCode.API_REQUEST_ERROR, "远程代码沙箱返回 HTTP " + httpStatus);
        }
        if (StringUtils.isBlank(responseStr)) {
            throw new BusinessException(ErrorCode.API_REQUEST_ERROR, "远程代码沙箱返回空响应");
        }
        ExecuteCodeResponse response;
        try {
            response = JSONUtil.toBean(responseStr, ExecuteCodeResponse.class);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.API_REQUEST_ERROR, "远程代码沙箱响应格式错误");
        }
        if (response == null || response.getStatus() == null) {
            throw new BusinessException(ErrorCode.API_REQUEST_ERROR, "远程代码沙箱响应缺少状态字段");
        }
        return response;
    }
}
