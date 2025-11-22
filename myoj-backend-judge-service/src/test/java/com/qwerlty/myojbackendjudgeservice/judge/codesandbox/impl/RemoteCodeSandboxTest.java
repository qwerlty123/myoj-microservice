package com.qwerlty.myojbackendjudgeservice.judge.codesandbox.impl;

import com.qwerlty.myojbackendcommon.exception.BusinessException;
import com.qwerlty.myojbackendjudgeservice.judge.codesandbox.SandboxConfigurationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteCodeSandboxTest {

    @Test
    void authenticationFailureIsReportedAsPermanentSandboxConfigurationError() {
        RemoteCodeSandbox sandbox = new RemoteCodeSandbox();

        SandboxConfigurationException exception = assertThrows(
                SandboxConfigurationException.class,
                () -> sandbox.parseResponse(403, "")
        );

        assertTrue(exception.getMessage().contains("认证"));
    }

    @Test
    void nonSuccessfulHttpResponseIsNotParsedAsAnEmptySandboxResponse() {
        RemoteCodeSandbox sandbox = new RemoteCodeSandbox();

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> sandbox.parseResponse(500, "{\"error\":\"boom\"}")
        );

        assertTrue(exception.getMessage().contains("HTTP 500"));
    }

    @Test
    void successfulButIncompleteResponseIsRejected() {
        RemoteCodeSandbox sandbox = new RemoteCodeSandbox();

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> sandbox.parseResponse(200, "{\"timestamp\":123}")
        );

        assertTrue(exception.getMessage().contains("状态字段"));
    }
}
