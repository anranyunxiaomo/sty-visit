package com.sty.visit.security.impl;

import com.sty.visit.security.ISecurityPolicy;
import org.springframework.stereotype.Component;
import java.util.regex.Pattern;

/**
 * 健硕版：默认安全策略实现
 * 采用“白名单+正则表达式”模型，彻底封死路径遍历与指令注入漏洞。
 */
@Component
public class DefaultSecurityPolicy implements ISecurityPolicy {

    // 严苛路径白名单：仅允许字母、数字、下划线、中划线、点及正斜杠
    private static final Pattern SAFE_PATH_PATTERN = Pattern.compile("^[a-zA-Z0-9/_\\-\\.\\s]+$");
    
    // 恶意载荷黑名单：严禁分号、管道符、反引号等 Shell 元字符
    private static final String[] DANGEROUS_PAYLOADS = {";", "|", "&", "`", "$", "(", ")", ">", "<"};

    @Override
    public void validatePath(String path) throws SecurityException {
        if (path == null || path.isEmpty()) return;

        // 1. 结构化校验
        if (!SAFE_PATH_PATTERN.matcher(path).matches()) {
            throw new SecurityException("路径包含非法字符，访问被拦截");
        }

        // 2. 深度穿透校验
        if (path.contains("..")) {
            throw new SecurityException("检测到路径遍历攻击 (..)，操作被终止");
        }

        // 3. 元字符注入校验
        for (String payload : DANGEROUS_PAYLOADS) {
            if (path.contains(payload)) {
                throw new SecurityException("检测到潜在的指令注入风险: " + payload);
            }
        }
    }
}
