package com.sty.visit.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 健硕性注解：自动校验并注入 SSH 会话
 * 标注此注解的方法将由 AOP 自动完成会话生存检查，开发者只需专注于业务逻辑
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresSshSession {
}
