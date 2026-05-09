package com.sty.visit.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sty.visit.model.Result;
import com.sty.visit.service.AuditService;
import com.sty.visit.service.CryptoService;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 连接书签管理器 (Bookmark Controller)
 * 实现远程连接凭据的命名存储与一键秒连。
 */
@RestController
@RequestMapping("/api/bookmarks")
public class BookmarkController {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BookmarkController.class);

    @org.springframework.beans.factory.annotation.Value("${visit.config-dir:config}")
    private String configDir;

    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    private CryptoService cryptoService;

    @Autowired
    private AuditService auditService;

    public static class Bookmark {
        private String id;
        private String name;
        private String host;
        private String port;
        private String user;
        private String pwd;  // 静态加密存储
        private String key;  // 静态加密存储 (针对专业版私钥)
        private boolean isKeyAuth;

        // 手动实现 Getters/Setters 以确保 Maven 编译兼容性
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public String getPort() { return port; }
        public void setPort(String port) { this.port = port; }
        public String getUser() { return user; }
        public void setUser(String user) { this.user = user; }
        public String getPwd() { return pwd; }
        public void setPwd(String pwd) { this.pwd = pwd; }
        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        public boolean isKeyAuth() { return isKeyAuth; }
        public void setKeyAuth(boolean keyAuth) { isKeyAuth = keyAuth; }
    }

    @GetMapping
    public Result<List<Bookmark>> getBookmarks(HttpServletRequest request) {
        try {
            File file = new File(configDir, "bookmarks.json");
            if (!file.exists()) return Result.success(new ArrayList<>());
            List<Bookmark> list = mapper.readValue(file, mapper.getTypeFactory().constructCollectionType(List.class, Bookmark.class));
            
            String authHeader = request.getHeader("Authorization");
            String token = (authHeader != null && authHeader.startsWith("Bearer ")) ? authHeader.substring(7) : null;
            
            // 将静态存储的密码转换为动态会话密文，以便客户端解密
            if (token != null) {
                byte[] sessionKey = cryptoService.getSessionKey(token);
                if (sessionKey != null) {
                    for (Bookmark b : list) {
                        try {
                            String plainPwd = cryptoService.decryptStatic(b.getPwd());
                            byte[] iv = cryptoService.generateIv();
                            byte[] encrypted = cryptoService.encrypt(plainPwd.getBytes(java.nio.charset.StandardCharsets.UTF_8), sessionKey, iv);
                            String dynamicCipher = "PROTECTED:AES:" + java.util.Base64.getEncoder().encodeToString(iv) + ":" + java.util.Base64.getEncoder().encodeToString(encrypted);
                            b.setPwd(dynamicCipher);
                        } catch (Exception ignored) {}
                    }
                }
            }
            
            return Result.success(list);
        } catch (Exception e) {
            return Result.success(new ArrayList<>());
        }
    }

    @PostMapping
    public Result<String> saveBookmark(HttpServletRequest request, @RequestBody Bookmark bookmark) {
        try {
            List<Bookmark> all = getBookmarks(request).getData();
            String authHeader = request.getHeader("Authorization");
            String token = (authHeader != null && authHeader.startsWith("Bearer ")) ? authHeader.substring(7) : null;
            
            // 物理对齐：处理动态加密 (Session Key) 与 静态存储 (Master Key) 的转换
            bookmark.setPwd(alignCredential(bookmark.getPwd(), token));
            bookmark.setKey(alignCredential(bookmark.getKey(), token));

            // 更新或新增
            if (bookmark.getId() == null) bookmark.setId(java.util.UUID.randomUUID().toString());
            all.removeIf(b -> b.getId().equals(bookmark.getId()));
            all.add(bookmark);

            File dir = new File(configDir);
            if (!dir.exists()) dir.mkdirs();
            mapper.writeValue(new File(configDir, "bookmarks.json"), all);
            
            auditService.log(request.getRemoteAddr(), "创建书签", "保存书签: " + bookmark.getName(), "成功");
            return Result.success("Saved");
        } catch (Exception e) {
            log.error("Save bookmark failed: {}", e.getMessage());
            return Result.error(500, "Failed: " + e.getMessage());
        }
    }

    private String alignCredential(String val, String token) throws Exception {
        if (val == null || val.isEmpty()) return val;
        
        String plain = val;
        // 1. 如果是动态加密格式，先利用当前会话 Token 还原明文
        if (val.startsWith("PROTECTED:AES:")) {
            if (token == null) throw new SecurityException("缺失认证令牌，无法处理加密凭据");
            plain = cryptoService.decryptWithToken(val.substring(14), token);
        }
        
        // 2. 将明文（或未改变的非加密串）转换为静态持久化加密格式
        if (!plain.contains(":")) {
            return cryptoService.encryptStatic(plain);
        }
        
        return plain;
    }

    @DeleteMapping
    public Result<String> deleteBookmark(HttpServletRequest request, @RequestParam String id) {
        try {
            List<Bookmark> all = getBookmarks(request).getData();
            all.removeIf(b -> b.getId().equals(id));
            mapper.writeValue(new File(configDir, "bookmarks.json"), all);
            auditService.log(request.getRemoteAddr(), "删除书签", "删除书签 ID: " + id, "成功");
            return Result.success("Deleted");
        } catch (Exception e) {
            return Result.error(500, "Failed: " + e.getMessage());
        }
    }
}
