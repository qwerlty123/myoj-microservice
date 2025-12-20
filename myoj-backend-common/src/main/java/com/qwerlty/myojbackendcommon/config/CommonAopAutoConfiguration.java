package com.qwerlty.myojbackendcommon.config;

import com.qwerlty.myojbackendcommon.aspect.AuthCheckAspect;
import com.qwerlty.myojbackendcommon.aspect.LogExecutionTimeAspect;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Common 模块 AOP 自动配置
 * 依赖 common 的服务会自动注册权限校验切面与用时记录切面
 */
@Configuration
public class CommonAopAutoConfiguration {

    @Bean
    public AuthCheckAspect authCheckAspect() {
        return new AuthCheckAspect();
    }

    @Bean
    public LogExecutionTimeAspect logExecutionTimeAspect() {
        return new LogExecutionTimeAspect();
    }
}
