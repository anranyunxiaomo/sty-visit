package com.sty.visit.aspect;

import com.sty.visit.annotation.AuditAction;
import com.sty.visit.service.AuditService;
import com.sty.visit.util.StyVisitConstants;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpServletRequest;

/**
 * 审计切面实现 (Observer/Decorator Pattern)
 * 实现业务日志的零侵入采集。
 */
@Aspect
@Component
public class AuditAspect {

    @Autowired
    private AuditService auditService;

    @Autowired
    private HttpServletRequest request;

    @AfterReturning(pointcut = "@annotation(auditAction)", returning = "result")
    public void logAfterSuccess(JoinPoint joinPoint, AuditAction auditAction, Object result) {
        String action = auditAction.value();
        String detail = extractDynamicDetail(joinPoint, auditAction.detail());
        String ip = request.getRemoteAddr();
        
        auditService.log(ip, action, detail, StyVisitConstants.STATUS_SUCCESS);
    }

    @AfterThrowing(pointcut = "@annotation(auditAction)", throwing = "e")
    public void logAfterError(JoinPoint joinPoint, AuditAction auditAction, Throwable e) {
        String action = auditAction.value();
        String detail = extractDynamicDetail(joinPoint, auditAction.detail()) + " (异常: " + e.getMessage() + ")";
        String ip = request.getRemoteAddr();
        
        try {
            auditService.log(ip, action, detail, StyVisitConstants.STATUS_FAILURE);
        } catch (Exception ignored) {}
    }

    /**
     * 物理加固：动态详情提取器
     * 从方法参数中自动嗅探关键字段（如 path, name, id）并拼接到审计日志中。
     */
    private String extractDynamicDetail(JoinPoint joinPoint, String baseDetail) {
        StringBuilder sb = new StringBuilder(baseDetail);
        try {
            Object[] args = joinPoint.getArgs();
            String[] paramNames = ((MethodSignature) joinPoint.getSignature()).getParameterNames();
            
            if (paramNames != null) {
                for (int i = 0; i < paramNames.length; i++) {
                    if ("path".equals(paramNames[i]) || "name".equals(paramNames[i]) || "id".equals(paramNames[i])) {
                        sb.append(" [").append(paramNames[i]).append(": ").append(args[i]).append("]");
                    }
                }
            }
        } catch (Exception e) {
            // 静默降级，确保审计本身不会导致业务崩溃
        }
        return sb.toString();
    }
}
