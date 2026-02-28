package com.qwerlty.myojbackendcommon.aspect;

import com.qwerlty.myojbackendcommon.annotation.LogExecutionTime;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;

/**
 * 仅用时记录 AOP 切面
 * 对标注了 @LogExecutionTime 的方法记录执行耗时（不校验权限）
 */
@Aspect
@Slf4j
public class LogExecutionTimeAspect {

    @Around("@annotation(logExecutionTime)")
    public Object logTime(ProceedingJoinPoint joinPoint, LogExecutionTime logExecutionTime) throws Throwable {
        long start = System.currentTimeMillis();
        String methodName = joinPoint.getSignature().getDeclaringTypeName() + "#" + joinPoint.getSignature().getName();
        try {
            Object result = joinPoint.proceed();
            long cost = System.currentTimeMillis() - start;
            log.info("接口用时: {} ms, 方法: {}", cost, methodName);
            return result;
        } catch (Throwable e) {
            long cost = System.currentTimeMillis() - start;
            log.warn("接口异常用时: {} ms, 方法: {}, 异常: {}", cost, methodName, e.getMessage());
            throw e;
        }
    }
}
