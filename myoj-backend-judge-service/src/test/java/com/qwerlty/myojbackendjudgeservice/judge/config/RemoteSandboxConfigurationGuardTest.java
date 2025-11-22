package com.qwerlty.myojbackendjudgeservice.judge.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RemoteSandboxConfigurationGuardTest {

    @Test
    void acceptsACompleteRemoteSandboxConfiguration() {
        RemoteSandboxConfigurationGuard guard = new RemoteSandboxConfigurationGuard(
                "http://124.221.250.220:8090/executeCode",
                "0123456789abcdef0123456789abcdef",
                120_000);

        assertDoesNotThrow(guard::validate);
    }

    @Test
    void rejectsAnEmptyOrWeakSharedSecret() {
        RemoteSandboxConfigurationGuard guard = new RemoteSandboxConfigurationGuard(
                "http://124.221.250.220:8090/executeCode",
                "",
                120_000);

        assertThrows(IllegalStateException.class, guard::validate);
    }
}
