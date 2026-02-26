package com.qwerlty.myojbackendcommon;

import com.qwerlty.myojbackendcommon.config.CommonAopAutoConfiguration;
import com.qwerlty.myojbackendcommon.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MyojBackendCommonApplicationTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(CommonAopAutoConfiguration.class);

    @Test
    void autoConfigurationRegistersGlobalExceptionHandler() {
        contextRunner.run(context ->
                org.assertj.core.api.Assertions.assertThat(context)
                        .hasSingleBean(GlobalExceptionHandler.class));
    }

}
