package com.sty.visit.controller;

import com.sty.visit.model.Result;
import com.sty.visit.annotation.AuditAction;
import com.sty.visit.util.StyVisitConstants;
import com.sty.visit.util.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Value("${visit.admin-password}")
    private String adminPassword;

    @Autowired
    private JwtUtil jwtUtil;

    private final Map<String, Integer> failureCount = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Long> lastAttemptTime = new java.util.concurrent.ConcurrentHashMap<>(); // 记录最后尝试时间
    private final Map<String, Long> lockoutTime = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 终极加固：防御性内存清扫器 (Memory Sweeper)
     * 防止伪造 IP 的洪水探测攻击导致字典膨胀和 OOM
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedRate = 3600000) // 每小时执行一次深度清扫
    public void cleanupMemory() {
        long now = System.currentTimeMillis();
        // 1. 清理已过期的锁定记录 (超过 5 分钟)
        lockoutTime.entrySet().removeIf(entry -> now - entry.getValue() > 5 * 60 * 1000);
        
        // 2. 清理沉寂的失败记录 (超过 1 小时未再次尝试的 IP)
        lastAttemptTime.entrySet().removeIf(entry -> {
            if (now - entry.getValue() > 3600000) {
                failureCount.remove(entry.getKey());
                return true;
            }
            return false;
        });
    }

    @javax.annotation.PostConstruct
    public void init() {
        // 第一道门槛：管理密码强度审计
        if (adminPassword == null || adminPassword.length() < 12) {
            try { Thread.sleep(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        
        if (adminPassword == null || adminPassword.length() < 12 || 
            !adminPassword.matches(".*[a-z].*") || !adminPassword.matches(".*[A-Z].*") || 
            !adminPassword.matches(".*[0-9].*")) {
            throw new IllegalStateException("\n[ADMIN SECURITY ERROR] 管理密码 (visit.admin-password) 强度不足！\n" +
                    "要求：长度至少 12 位，且必须同时包含大小写字母与数字。\n" +
                    "请立即更新 application.yml 以确保首道防线不被暴破。");
        }
    }

    @Autowired
    private com.sty.visit.service.CryptoService cryptoService;

    @Autowired
    private com.sty.visit.service.AuditService auditService;

    @PostMapping("/login")
    @AuditAction(value = StyVisitConstants.ACTION_LOGIN, detail = "管理员尝试登录系统")
    public Result<Map<String, String>> login(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String password = body.get("password");
        String ip = request.getRemoteAddr();
        
        // 检查锁定
        if (lockoutTime.containsKey(ip)) {
            if (System.currentTimeMillis() - lockoutTime.get(ip) < 5 * 60 * 1000) {
                auditService.log(ip, "LOGIN_BLOCKED", "由于多次失败尝试，IP 被暂时封禁", "LOCKED");
                return Result.error(429, "尝试次数过多，IP已被锁定。请稍后再试。");
            }
            lockoutTime.remove(ip);
            failureCount.remove(ip);
        }

        if (adminPassword.equals(password)) {
            failureCount.remove(ip);
            String token = jwtUtil.createToken("admin");
            
            // 终极加固：为当前会话生成唯一的 AES-256 对等秘钥
            String sessionKey = cryptoService.generateSessionKey(token);
            
            Map<String, String> result = new HashMap<>();
            result.put("token", token);
            result.put("sessionKey", sessionKey);
            return Result.success(result);
        } else {
            int attempts = failureCount.getOrDefault(ip, 0) + 1;
            failureCount.put(ip, attempts);
            lastAttemptTime.put(ip, System.currentTimeMillis()); // 记录活动轨迹
            
            if (attempts >= 5) {
                lockoutTime.put(ip, System.currentTimeMillis());
            }
            return Result.error(401, "无效的管理密码。当前尝试次数 " + attempts + "/5");
        }
    }

    @GetMapping("/check")
    public Result<String> check(@RequestHeader("Authorization") String token) {
        if (token != null && token.startsWith("Bearer ")) {
            String realToken = token.substring(7);
            if (jwtUtil.verify(realToken)) {
                return Result.success("OK");
            }
        }
        return Result.error(401, "登录凭证已过期");
    }

    /**
     * 终极加固：管理登出协议
     * 在销毁本地会话的同时，物理清空服务端近期审计记录缓存
     */
    @PostMapping("/logout")
    @AuditAction(value = StyVisitConstants.ACTION_LOGOUT, detail = "管理员主动注销会话")
    public Result<String> logout(@RequestHeader("Authorization") String token, HttpServletRequest request) {
        String realToken = (token != null && token.startsWith(StyVisitConstants.AUTH_PREFIX)) ? token.substring(StyVisitConstants.AUTH_PREFIX.length()) : token;
        if (realToken != null) {
            cryptoService.removeSessionKey(realToken);
        }
        
        auditService.clearMemoryLogs();
        return Result.success("注销成功");
    }
}
