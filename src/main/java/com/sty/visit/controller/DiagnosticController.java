package com.sty.visit.controller;

import com.sty.visit.model.Result;
import com.sty.visit.service.AuditService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * 全链路诊断控制器：接收来自各端的健康数据与异常上报
 */
@RestController
@RequestMapping("/api/diag")
public class DiagnosticController {

    @Autowired
    private AuditService auditService;

    @PostMapping("/report")
    public Result<String> reportIssue(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String type = body.getOrDefault("type", "CLIENT_ERROR");
        String detail = body.getOrDefault("detail", "unknown");
        String platform = body.getOrDefault("platform", "UNKNOWN");
        
        auditService.log(request.getRemoteAddr(), type, detail, "ERROR", platform);
        return Result.success("Issue reported and audited.");
    }
}
