package com.sty.visit.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 业务审计注解 (Decorator Pattern)
 * 标记该注解的方法将由 AuditAspect 自动记录操作日志。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuditAction {
    String value() default ""; // 动作名称
    String detail() default ""; // 动作描述
}
