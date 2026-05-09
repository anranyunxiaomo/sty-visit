package com.sty.visit.controller;

import com.sty.visit.model.Result;
import com.sty.visit.service.AuditService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 终极加固：审计日志管理接口
 * 提供对系统操作记录的导出与查询能力。
 */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    @Autowired
    private AuditService auditService;

    @GetMapping("/logs")
    public Result<List<com.sty.visit.model.AuditEntry>> getLogs() {
        return Result.success(auditService.getRecentLogs());
    }

    @GetMapping("/stats")
    public Result<java.util.Map<String, Object>> getStats() {
        java.util.Map<String, Object> stats = new java.util.HashMap<>();
        stats.put("totalThreats", auditService.getTotalThreats());
        return Result.success(stats);
    }

    /**
     * 隐私加固：物理清空内存审计缓冲区
     */
    @DeleteMapping("/memory")
    public Result<String> deleteLogs() {
        auditService.clearMemoryLogs();
        return Result.success("Memory logs purged.");
    }
}
