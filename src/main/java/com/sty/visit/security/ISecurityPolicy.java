package com.sty.visit.security;

/**
 * 安全策略契约：定义业务沙盒的安全边界
 * 实现安全逻辑的插件化拆分
 */
public interface ISecurityPolicy {
    /**
     * 校验路径是否在业务允许的沙盒范围内
     */
    void validatePath(String path) throws SecurityException;
}
