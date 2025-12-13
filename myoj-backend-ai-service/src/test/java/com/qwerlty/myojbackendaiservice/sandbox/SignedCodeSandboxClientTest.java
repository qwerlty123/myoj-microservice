package com.qwerlty.myojbackendaiservice.sandbox;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SignedCodeSandboxClientTest {

    @Test
    void signsTheExactBodyUsingTheSandboxProtocol() {
        assertThat(SignedCodeSandboxClient.sign("secret", 123L, "{\"a\":1}"))
                .isEqualTo("3f9ad62954696e793fcbb94a80946e0ddeede9b8e19f2b79567b32c5a10fb24e");
    }
}
