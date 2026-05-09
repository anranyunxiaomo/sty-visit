package com.sty.visit.controller;

import com.sty.visit.model.Result;
import com.sty.visit.service.SystemConfigService;
import com.sty.visit.service.AuditService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * 系统配置控制器：暴露物理管控接口
 * 支持 Native 端实时调整 H5 准入策略
 */
@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private static final Logger log = LoggerFactory.getLogger(ConfigController.class);

    @Autowired
    private SystemConfigService systemConfigService;

    @Autowired
    private AuditService auditService;

    @org.springframework.beans.factory.annotation.Value("${visit.admin-password}")
    private String adminPassword;

    @org.springframework.beans.factory.annotation.Value("${visit.config-dir:config}")
    private String configDir;

    @PostMapping("/wipe")
    public Result<String> wipeAllData(@RequestBody Map<String, String> body) {
        String password = body.get("password");
        if (!adminPassword.equals(password)) {
            log.error("🛡️ WIPE DENIED: Invalid password attempt from unauthorized source.");
            return Result.error(401, "密钥校验失败，无权执行物理擦除");
        }
        
        log.warn("🛡️ EMERGENCY WIPE INITIATED! Destroying all physical data...");
        
        // 1. 物理删除配置文件
        String[] filesToDestroy = {
            configDir + "/bookmarks.json",
            configDir + "/snippets.json",
            configDir + "/transfers.json",
            "logs/threat_stats.dat"
        };
        for (String path : filesToDestroy) {
            File f = new File(path);
            if (f.exists()) {
                if (f.delete()) log.info("🛡️ Privacy Wipe: Destroyed {}", path);
            }
        }
        
        // 2. 调用审计服务执行全量销毁（内存+磁盘日志）
        auditService.clearAllLogs();
        
        return Result.success("Wipe Complete");
    }

    @GetMapping("/h5/status")
    public Result<Map<String, Object>> getH5Status() {
        Map<String, Object> res = new HashMap<>();
        res.put("enabled", systemConfigService.isH5Enabled());
        return Result.success(res);
    }

    @PostMapping("/h5/toggle")
    public Result<String> toggleH5(@RequestBody Map<String, Boolean> body) {
        Boolean enabled = body.get("enabled");
        if (enabled != null) {
            systemConfigService.setH5Enabled(enabled);
        }
        return Result.success("Policy Updated");
    }

    @GetMapping("/audit/retention")
    public Result<Map<String, Object>> getAuditRetention() {
        Map<String, Object> res = new HashMap<>();
        res.put("days", systemConfigService.getAuditRetentionDays());
        return Result.success(res);
    }

    @PostMapping("/audit/retention")
    public Result<String> updateAuditRetention(@RequestBody Map<String, Integer> body) {
        Integer days = body.get("days");
        if (days != null && days > 0) {
            systemConfigService.setAuditRetentionDays(days);
            return Result.success("Retention Updated");
        }
        return Result.error(400, "Invalid days");
    }
}
