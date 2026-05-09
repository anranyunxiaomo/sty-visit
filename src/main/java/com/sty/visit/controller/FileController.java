package com.sty.visit.controller;

import com.sty.visit.annotation.RequiresSshSession;
import com.sty.visit.annotation.AuditAction;
import lombok.extern.slf4j.Slf4j;
import com.sty.visit.model.FileInfo;
import com.sty.visit.model.FileSaveRequest;
import com.sty.visit.model.Result;
import com.sty.visit.util.StyVisitConstants;
import com.sty.visit.service.AuditService;
import com.sty.visit.service.IFileService;
import com.sty.visit.service.SshSessionManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 终极健硕版文件控制器
 * 通过 AOP 注解实现零冗余校验，专注于业务契约的分发
 */
@Slf4j
@RestController
@RequestMapping("/api/file")
public class FileController {
    
    @Autowired
    private SshSessionManager sshSessionManager;
    
    @Autowired
    private IFileService fileService;
    
    @Autowired
    private AuditService auditService;

    private static final Map<String, TokenInfo> downloadTokenCache = new ConcurrentHashMap<>();
    private static class TokenInfo { String path, sessionKey; long expiry; }

    /**
     * 行为审计与资源收割：每 10 分钟物理擦除过期下载令牌，防止内存泄露
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedRate = 600000)
    public void cleanupTokens() {
        long now = System.currentTimeMillis();
        int before = downloadTokenCache.size();
        downloadTokenCache.entrySet().removeIf(entry -> now > entry.getValue().expiry);
        int after = downloadTokenCache.size();
        if (before != after) {
            log.info("🛡️ Token Reaper: Cleaned {} expired download tokens.", before - after);
        }
    }

    @GetMapping("/token")
    @RequiresSshSession
    public Result<Map<String, String>> getDownloadToken(@RequestParam String path, HttpServletRequest request) {
        String token = UUID.randomUUID().toString();
        TokenInfo info = new TokenInfo();
        info.sessionKey = sshSessionManager.getSessionKey(request);
        info.path = path;
        info.expiry = System.currentTimeMillis() + 60000;
        downloadTokenCache.put(token, info);
        return Result.success(java.util.Collections.singletonMap("token", token));
    }

    @GetMapping("/list")
    @RequiresSshSession
    public Result<List<FileInfo>> list(@RequestParam(defaultValue = "") String path, HttpServletRequest request) throws Exception {
        return Result.success(fileService.listFiles(sshSessionManager.getSession(request), path));
    }

    @GetMapping("/content")
    @RequiresSshSession
    public Result<String> getContent(@RequestParam String path, HttpServletRequest request) throws Exception {
        return Result.success(fileService.getFileContent(sshSessionManager.getSession(request), path));
    }

    @PostMapping("/save")
    @RequiresSshSession
    @AuditAction(value = StyVisitConstants.ACTION_FILE_SAVE, detail = "保存远程文件")
    public Result<String> saveContent(@RequestBody @javax.validation.Valid FileSaveRequest body, HttpServletRequest request) throws Exception {
        fileService.saveFile(sshSessionManager.getSession(request), body.getPath(), body.getContent());
        return Result.success("保存成功");
    }

    @DeleteMapping
    @RequiresSshSession
    @AuditAction(value = StyVisitConstants.ACTION_FILE_DELETE, detail = "物理粉碎远程文件/目录")
    public Result<String> delete(@RequestParam String path, HttpServletRequest request) throws Exception {
        fileService.deletePath(sshSessionManager.getSession(request), path);
        return Result.success("删除成功");
    }

    @PostMapping("/upload")
    @RequiresSshSession
    @AuditAction(value = StyVisitConstants.ACTION_FILE_UPLOAD, detail = "上传物理文件至服务器")
    public Result<String> upload(@RequestParam("file") org.springframework.web.multipart.MultipartFile file, 
                                 @RequestParam("path") String path, 
                                 HttpServletRequest request) throws Exception {
        if (file.isEmpty()) return Result.error(400, "文件为空");
        // 路径健壮性处理：确保不会出现双斜杠
        String remotePath = path.endsWith("/") ? path + file.getOriginalFilename() : path + "/" + file.getOriginalFilename();
        
        com.jcraft.jsch.Session session = sshSessionManager.getSession(request);
        fileService.uploadFile(session, remotePath, file.getInputStream());
        return Result.success("上传成功");
    }

    @GetMapping("/download")
    public void download(@RequestParam String token, HttpServletRequest request, javax.servlet.http.HttpServletResponse response) throws Exception {
        TokenInfo info = downloadTokenCache.get(token);
        if (info == null || System.currentTimeMillis() > info.expiry) {
            response.setStatus(403);
            response.getWriter().write("下载链接已失效");
            return;
        }
        
        // [ALIGNMENT] 手动补全下载审计，对齐“全量审计”规格
        auditService.log(request.getRemoteAddr(), StyVisitConstants.ACTION_FILE_DOWNLOAD, "下载远程文件: " + info.path, StyVisitConstants.STATUS_SUCCESS);
        // 由于是无状态下载，需要使用存储在 token 中的 sessionKey 重建或查找会话
        com.jcraft.jsch.Session session = sshSessionManager.getSessionByKey(info.sessionKey);
        if (session == null || !session.isConnected()) {
            response.setStatus(401);
            response.getWriter().write("SSH 会话已断开");
            return;
        }
        
        String filename = info.path.substring(info.path.lastIndexOf('/') + 1);
        response.setContentType("application/octet-stream");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + java.net.URLEncoder.encode(filename, "UTF-8") + "\"");
        
        fileService.downloadFile(session, info.path, response.getOutputStream());
        downloadTokenCache.remove(token);
    }

    @GetMapping("/pwd")
    @RequiresSshSession
    public Result<Map<String, String>> pwd(HttpServletRequest request) throws Exception {
        String path = fileService.getCurrentPath(sshSessionManager.getSession(request));
        return Result.success(java.util.Collections.singletonMap("path", path));
    }
}
