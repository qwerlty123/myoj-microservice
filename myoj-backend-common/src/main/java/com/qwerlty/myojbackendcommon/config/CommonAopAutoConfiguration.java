package com.qwerlty.myojbackendcommon.config;

import com.qwerlty.myojbackendcommon.aspect.AuthCheckAspect;
import com.qwerlty.myojbackendcommon.aspect.LogExecutionTimeAspect;
import com.qwerlty.myojbackendcommon.exception.GlobalExceptionHandler;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

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

    /**
     * Common is a sibling package of every service application, so component scanning does
     * not discover its controller advice. Register it through auto-configuration instead.
    */
    @Bean
    @ConditionalOnMissingBean(GlobalExceptionHandler.class)
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }

    /**
     * Springfox 2.10.5 与 Spring Boot 2.6 的 Actuator 路由兼容性修复。
     *
     * Spring Boot 2.6 为部分 HandlerMapping 使用 PathPatternParser，
     * Springfox 2.10.5 在扫描这些映射时会访问空的 PatternsRequestCondition，
     * 导致 documentationPluginsBootstrapper 启动失败。只从 Springfox 的
     * handler provider 中移除这类映射，保留 Actuator 本身和业务路由。
     */
    @Bean
    public static BeanPostProcessor springfoxHandlerProviderBeanPostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                if (!isSpringfoxHandlerProvider(bean)) {
                    return bean;
                }

                Field handlerMappingsField = ReflectionUtils.findField(bean.getClass(), "handlerMappings");
                if (handlerMappingsField == null) {
                    return bean;
                }

                ReflectionUtils.makeAccessible(handlerMappingsField);
                Object handlerMappings = ReflectionUtils.getField(handlerMappingsField, bean);
                if (!(handlerMappings instanceof List)) {
                    return bean;
                }

                ((List<?>) handlerMappings).removeIf(SpringfoxCompatibility::usesPathPatternParser);
                return bean;
            }
        };
    }

    private static boolean isSpringfoxHandlerProvider(Object bean) {
        String className = bean.getClass().getName();
        return className.contains("springfox.documentation.spring.web.plugins.WebMvcRequestHandlerProvider")
                || className.contains("springfox.documentation.spring.web.plugins.WebFluxRequestHandlerProvider");
    }

    private static final class SpringfoxCompatibility {

        private SpringfoxCompatibility() {
        }

        private static boolean usesPathPatternParser(Object handlerMapping) {
            Method getPatternParser = ReflectionUtils.findMethod(handlerMapping.getClass(), "getPatternParser");
            if (getPatternParser == null) {
                return false;
            }

            ReflectionUtils.makeAccessible(getPatternParser);
            return ReflectionUtils.invokeMethod(getPatternParser, handlerMapping) != null;
        }
    }
}
