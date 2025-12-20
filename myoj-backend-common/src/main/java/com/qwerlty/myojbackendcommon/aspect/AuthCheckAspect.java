package com.qwerlty.myojbackendcommon.aspect;

import com.qwerlty.myojbackendcommon.annotation.AuthCheck;
import com.qwerlty.myojbackendcommon.common.ErrorCode;
import com.qwerlty.myojbackendcommon.constant.UserConstant;
import com.qwerlty.myojbackendcommon.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.util.StringUtils;

import javax.servlet.http.HttpServletRequest;

/**
 * 权限校验 + 用时记录 AOP 切面
 * 对标注了 @AuthCheck 的方法进行：管理员/角色校验、方法执行耗时记录
 */
@Aspect
@Slf4j
public class AuthCheckAspect {

    private static final String HEADER_USER_ROLE = "X-user-Role";

    @Around("@annotation(authCheck)")
    public Object doAuthCheckAndLogTime(ProceedingJoinPoint joinPoint, AuthCheck authCheck) throws Throwable {
        String mustRole = authCheck.mustRole();
        if (StringUtils.hasText(mustRole)) {
            RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
            if (requestAttributes == null || !(requestAttributes instanceof ServletRequestAttributes)) {
                throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无法获取请求上下文");
            }
            HttpServletRequest request = ((ServletRequestAttributes) requestAttributes).getRequest();
            String userRole = request.getHeader(HEADER_USER_ROLE);
            // 无角色或空角色一律拒绝
            if (!StringUtils.hasText(userRole)) {
                log.warn("权限校验失败: 未获取到用户角色, 方法={}", joinPoint.getSignature());
                throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限执行该操作");
            }
            // admin 拥有所有权限（user 能做的 admin 都能做）；非 admin 则必须满足 mustRole
            boolean allowed = UserConstant.ADMIN_ROLE.equals(userRole) || mustRole.equals(userRole);
            if (!allowed) {
                log.warn("权限校验失败: 需要角色={}, 当前角色={}, 方法={}", mustRole, userRole, joinPoint.getSignature());
                throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限执行该操作");
            }
        }

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
