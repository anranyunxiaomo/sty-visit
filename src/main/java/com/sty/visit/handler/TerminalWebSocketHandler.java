package com.sty.visit.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSch;
import com.sty.visit.util.DirectSocketFactory;
import com.jcraft.jsch.Session;
import com.sty.visit.util.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.*;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class TerminalWebSocketHandler extends TextWebSocketHandler implements org.springframework.beans.factory.DisposableBean {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TerminalWebSocketHandler.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private JwtUtil jwtUtil;

    @Value("${visit.ssh-host:127.0.0.1}")
    private String sshHost;

    @Value("${visit.ssh-port:22}")
    private int sshPort;

    @Value("${visit.max-sessions:50}")
    private int maxSessions;

    private static final java.util.concurrent.ThreadPoolExecutor executor = new java.util.concurrent.ThreadPoolExecutor(
            10, 100, 60L, java.util.concurrent.TimeUnit.SECONDS,
            new java.util.concurrent.LinkedBlockingQueue<>(100),
            new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy()
    );
    private final java.util.concurrent.ScheduledExecutorService reaper = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
    private static final java.util.concurrent.Semaphore connectionSemaphore = new java.util.concurrent.Semaphore(10);
    private static final Map<String, SshContext> sshContexts = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger sessionCounter = new java.util.concurrent.atomic.AtomicInteger(0);

    @Override
    public void destroy() {
        log.info("Application shutting down, cleaning up {} SSH sessions...", sshContexts.size());
        sshContexts.values().forEach(ctx -> {
            try {
                if (ctx.channel != null) ctx.channel.disconnect();
                if (ctx.session != null) ctx.session.disconnect();
            } catch (Exception e) {
                // 静默处理清理时的异常
            }
        });
        sshContexts.clear();
        executor.shutdownNow();
    }

    @Autowired
    private com.sty.visit.service.AuditService auditService;

    @Autowired
    private com.sty.visit.service.CryptoService cryptoService;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.info("[WS] New connection established: {}", session.getId());
        if (sessionCounter.get() >= maxSessions) {
            safeSend(session, new org.springframework.web.socket.BinaryMessage("\r\n[ERROR] 服务器连接数已达上限\r\n".getBytes()));
            session.close(CloseStatus.SERVER_ERROR);
            return;
        }

        // 终极加固：从拦截器预置的属性中读取鉴权状态 (Stealth Handshake)
        Map<String, Object> attrs = session.getAttributes();
        Object verified = attrs.get("auth_token_verified");

        if (verified == null || !(Boolean) verified) {
            try { Thread.sleep(java.util.concurrent.ThreadLocalRandom.current().nextInt(300, 800)); } catch (InterruptedException ignored) {}
            safeSend(session, new org.springframework.web.socket.BinaryMessage("\r\n认证失败，握手已拦截\r\n".getBytes()));
            session.close();
            auditService.log(session.getRemoteAddress().getHostString(), "终端连接拒绝", "隐形握手鉴权未通过", "失败");
            return;
        }

        // 终极资源加固：恢复“僵尸连接”收割任务，15秒内未完成握手则强制切断
        reaper.schedule(() -> {
            try {
                if (session.isOpen() && !sshContexts.containsKey(session.getId())) {
                    log.warn("Terminating zombie session: {}", session.getId());
                    session.close(new CloseStatus(4001, "Authentication Timeout"));
                }
            } catch (Exception e) { log.debug("Reaper error: {}", e.getMessage()); }
        }, 15, java.util.concurrent.TimeUnit.SECONDS);

        auditService.log(session.getRemoteAddress().getHostString(), "终端链路建立", "WebSocket 链路已建立", "成功");

        // [AUTOMATIC AUTH] 物理闭环：如果握手中已携带凭证，则直接发起 SSH 指令握手
        if (attrs.containsKey("x-ssh-host") && attrs.containsKey("x-ssh-user")) {
            String host = (String) attrs.get("x-ssh-host");
            String port = (String) attrs.get("x-ssh-port");
            String user = (String) attrs.get("x-ssh-user");
            String pwd = (String) attrs.get("x-ssh-pwd");
            
            executor.execute(() -> {
                try {
                    int portNum = 22;
                    try {
                        if (port != null && !port.trim().isEmpty()) {
                            portNum = Integer.parseInt(port.trim());
                        }
                    } catch (Exception ignored) {}
                    establishSshTunnel(session, host, portNum, user, pwd, 80, 24);
                } catch (Exception e) {
                    log.warn("Automatic SSH Auth Failed: {}", e.getMessage());
                }
            });
        } else {
            safeSend(session, new org.springframework.web.socket.BinaryMessage("\r\n[SYSTEM] 隐形验证通过，等待终端握手...\r\n".getBytes()));
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        SshContext ctx = sshContexts.get(session.getId());
        String payload = message.getPayload();

        // 1. 处理连接认证 (仅当自动认证未执行或未完成时)
        if (ctx == null) {
            synchronized (session) {
                if (sshContexts.containsKey(session.getId())) return;
                
                if (payload == null || payload.trim().isEmpty()) return;
                
                JsonNode root;
                try {
                    root = objectMapper.readTree(payload);
                } catch (com.fasterxml.jackson.core.JsonParseException e) {
                    log.warn("Invalid JSON received before auth: {}", payload);
                    return; // 静默忽略非 JSON 报文，防止系统崩溃
                }
                
                if (!"auth".equals(root.path("type").asText())) return;

                if (!connectionSemaphore.tryAcquire()) {
                    safeSend(session, new TextMessage("\r\n[BUSY] 服务器忙，请稍候...\r\n"));
                    return;
                }
                try {
                    String sshU = root.path("sshUser").asText();
                    String sshP = root.path("sshPwd").asText();
                    String sshH = root.path("sshHost").asText(sshHost);
                    int sshPortNum = root.path("sshPort").asInt(sshPort);
                    int cols = root.path("cols").asInt(80);
                    int rows = root.path("rows").asInt(24);
                    log.info("[AUTH] Attempting SSH tunnel for user: {} to {}:{}", sshU, sshH, sshPortNum);
                    establishSshTunnel(session, sshH, sshPortNum, sshU, sshP, cols, rows);
                } catch (Exception e) {
                    log.error("[AUTH] Handshake failed: {}", e.getMessage());
                    safeSend(session, new TextMessage("\r\n[SSH ERROR] " + e.getMessage() + "\r\n"));
                } finally {
                    connectionSemaphore.release();
                }
            }
            return;
        }

        // 2. 处理窗口缩放
        if (payload.contains("\"type\":\"resize\"")) {
            try {
                JsonNode root = objectMapper.readTree(payload);
                int cols = root.path("cols").asInt(80);
                int rows = root.path("rows").asInt(24);
                ctx.channel.setPtySize(cols, rows, 0, 0);
            } catch (Exception ignored) {}
            return;
        }

        // 3. 处理常规指令下发
        try {
            ctx.setLastActiveTime(System.currentTimeMillis());
            if (payload.isEmpty()) return;
            
            // [ALIGNMENT] 实装终端隐形审计 (Stealth Log)
            auditService.log(session.getRemoteAddress().getHostString(), "终端指令执行", payload.trim(), "成功");
            
            ctx.os.write(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            ctx.os.flush();
        } catch (Exception e) {
            log.error("SSH Connect Failed: {}", e.getMessage());
            // [UX FIX] 向客户端回发明确的错误指令，防止界面假死
            try {
                session.sendMessage(new TextMessage("ERROR: SSH Connect Failed: " + e.getMessage()));
            } catch (Exception ignored) {}
            
            throw e;
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        SshContext ctx = sshContexts.get(session.getId());
        if (ctx != null) {
            ctx.setLastActiveTime(System.currentTimeMillis());
            try {
                ctx.getOs().write(message.getPayload().array());
                ctx.getOs().flush();
            } catch (Exception e) {}
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        SshContext ctx = sshContexts.remove(session.getId());
        if (ctx != null) {
            sessionCounter.decrementAndGet();
            if (ctx.channel != null) ctx.channel.disconnect();
            if (ctx.session != null) ctx.session.disconnect();
        }
        log.info("SSH Session Closed: {}", session.getId());
    }

    private void safeSend(WebSocketSession session, org.springframework.web.socket.WebSocketMessage<?> message) {
        if (session == null || !session.isOpen()) return;
        synchronized (session) {
            try {
                session.sendMessage(message);
            } catch (IOException e) {
                log.warn("WebSocket send failed: {}", e.getMessage());
            }
        }
    }


    /**
     * [CORE FIX] 物理 SSH 隧道建立：整合前缀剥离与动态解密
     */
    private void establishSshTunnel(WebSocketSession session, String host, int port, String user, String pwd, int cols, int rows) throws Exception {
        // [PHYSICAL FIX] 物理防御：强制规整端口，防止空字符串导致上层解析崩溃
        if (port <= 0) port = 22;
        
        if (pwd != null) {
            // [UX FIX] 物理兼容：无论密码是什么格式，前端都可能使用了 Base64 包装，必须先尝试解码
            try {
                String decoded = new String(java.util.Base64.getDecoder().decode(pwd), java.nio.charset.StandardCharsets.UTF_8);
                // 防御误伤：只有当解码后是合法的可读字符时才采纳，避免把凑巧合法的短密码转成乱码
                if (!decoded.contains("\ufffd")) {
                    pwd = decoded;
                }
            } catch (Exception ignored) {
                // 如果解码失败，保持原样（可能是未被 Base64 包装的明文或密文）
            }

            // 1. 动态层解密：如果是带有 PROTECTED:AES: 前缀的动态包装
            if (pwd.startsWith("PROTECTED:AES:")) {
                pwd = pwd.substring(14);
                String token = (String) session.getAttributes().get("raw_auth_token");
                if (token != null) {
                    try {
                        pwd = cryptoService.decryptWithToken(pwd, token);
                    } catch (Exception e) {
                        log.error("🛡️ WebSocket 动态解密失败: {}", e.getMessage());
                        throw new SecurityException("终端凭证解密失败，请重新建立会话。");
                    }
                }
            }

            // 2. 静态层解密：如果是由后端直接存入数据库的静态书签密文 (iv:cipher) 或剥开动态层后的静态书签
            if (pwd != null && pwd.contains(":")) {
                try {
                    String decrypted = cryptoService.decryptStatic(pwd);
                    if (decrypted != null) {
                        pwd = decrypted;
                    }
                } catch (Exception ignored) {
                    // 如果它只是一个刚好带冒号的普通明文密码，解密会抛异常，保留原样即可
                }
            }
        }

        safeSend(session, new TextMessage("\r\n[INFO] 正在解析目标地址: " + host + ":" + port + "...\r\n"));
        JSch jsch = new JSch();
        
        // [PHYSICAL FIX] 物理对齐：增加 SSH 私钥鉴权支持
        if (pwd != null && pwd.startsWith("-----BEGIN")) {
            // 如果凭据是以私钥格式开头，则作为 Identity 加载
            jsch.addIdentity("visit-key", pwd.getBytes(java.nio.charset.StandardCharsets.UTF_8), null, null);
            pwd = null; // 清除密码，防止干扰
        }

        Session jschSession = jsch.getSession(user, host, port);
        
        if (pwd != null) {
            jschSession.setPassword(pwd);
            jschSession.setUserInfo(new SshUserInfo(pwd));
        }
        
        // [CORE FIX] 注入物理直连工厂，绕过系统 SOCKS 代理
        jschSession.setSocketFactory(new DirectSocketFactory(10000));
        
        Properties config = new Properties();
        config.put("StrictHostKeyChecking", "no");
        // [PHYSICAL FIX] 算法同步：对齐 SshSessionManager，解决部分旧版服务器无法连接的问题
        config.put("kex", "ecdh-sha2-nistp256,ecdh-sha2-nistp384,ecdh-sha2-nistp521,diffie-hellman-group-exchange-sha256,diffie-hellman-group14-sha256,diffie-hellman-group14-sha1,diffie-hellman-group-exchange-sha1,diffie-hellman-group1-sha1");
        config.put("server_host_key", "ssh-ed25519,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,rsa-sha2-512,rsa-sha2-256,ssh-rsa,ssh-dss");
        config.put("PubkeyAcceptedAlgorithms", "ssh-ed25519,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,rsa-sha2-512,rsa-sha2-256,ssh-rsa");
        config.put("PreferredAuthentications", "publickey,password,keyboard-interactive");
        config.put("MaxAuthTries", "3");
        config.put("UseDNS", "no");
        
        jschSession.setConfig(config);
        jschSession.setServerAliveInterval(30000);
        
        safeSend(session, new TextMessage("[INFO] 正在尝试建立 TCP 链路...\r\n"));
        jschSession.connect(10000);
        safeSend(session, new TextMessage("[INFO] TCP 链路已建立，正在进行身份验证...\r\n"));

        ChannelShell channel = (ChannelShell) jschSession.openChannel("shell");
        channel.setPty(true);
        channel.setPtyType("xterm-256color");
        channel.setPtySize(cols, rows, 0, 0);
        
        InputStream is = channel.getInputStream();
        OutputStream os = channel.getOutputStream();
        safeSend(session, new TextMessage("[INFO] 身份验证通过，正在请求交互式 Shell...\r\n"));
        channel.connect(5000);
        safeSend(session, new TextMessage("[INFO] Shell 分配成功，会话已激活。\r\n\r\n"));

        SshContext newCtx = new SshContext();
        newCtx.session = jschSession;
        newCtx.channel = channel;
        newCtx.is = is;
        newCtx.os = os;
        newCtx.wsSession = session;
        newCtx.setLastActiveTime(System.currentTimeMillis());
        sshContexts.put(session.getId(), newCtx);
        sessionCounter.incrementAndGet();

        executor.execute(() -> {
            try (InputStream inputStream = is) {
                // [UX FIX] 延迟注入：等待远程服务器的 .bashrc 或 profile 加载完毕后再注入，防止别名被覆盖
                try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
                os.write("alias ls='ls -1 --color=auto -F --group-directories-first' && clear\r".getBytes());
                os.flush();

                byte[] readBuffer = new byte[1024 * 32];
                int len;
                while ((len = inputStream.read(readBuffer)) != -1) {
                    if (session.isOpen()) {
                        byte[] payloadBytes = java.util.Arrays.copyOfRange(readBuffer, 0, len);
                        safeSend(session, new BinaryMessage(payloadBytes));
                    } else break;
                }
            } catch (IOException e) { log.info("[SSH] IO Stream closed for session: {}", session.getId()); }
        });

        safeSend(session, new TextMessage("\r\n[SYSTEM] SSH 隧道已启动\r\n"));
        log.info("[SSH] Tunnel active for session: {}", session.getId());
    }

    private static class SshContext {
        private Session session;
        private ChannelShell channel;
        private InputStream is;
        private OutputStream os;
        private WebSocketSession wsSession;
        private long lastActiveTime;

        public Session getSession() { return this.session; }
        public void setSession(Session session) { this.session = session; }
        public ChannelShell getChannel() { return this.channel; }
        public void setChannel(ChannelShell channel) { this.channel = channel; }
        public InputStream getIs() { return this.is; }
        public void setIs(InputStream is) { this.is = is; }
        public OutputStream getOs() { return this.os; }
        public void setOs(OutputStream os) { this.os = os; }
        public WebSocketSession getWsSession() { return this.wsSession; }
        public void setWsSession(WebSocketSession wsSession) { this.wsSession = wsSession; }
        public long getLastActiveTime() { return this.lastActiveTime; }
        public void setLastActiveTime(long lastActiveTime) { this.lastActiveTime = lastActiveTime; }
    }

    private static class SshUserInfo implements com.jcraft.jsch.UserInfo, com.jcraft.jsch.UIKeyboardInteractive {
        private String password;
        public SshUserInfo(String password) { this.password = password; }
        @Override public String getPassphrase() { return null; }
        @Override public String getPassword() { return password; }
        @Override public boolean promptPassword(String message) { return true; }
        @Override public boolean promptPassphrase(String message) { return true; }
        @Override public boolean promptYesNo(String message) { return true; }
        @Override public void showMessage(String message) { log.debug("SSH UserInfo: {}", message); }
        @Override public String[] promptKeyboardInteractive(String destination, String name, String instruction, String[] prompt, boolean[] echo) {
            String[] response = new String[prompt.length];
            for (int i = 0; i < prompt.length; i++) {
                if (prompt[i] != null) response[i] = password;
            }
            return response;
        }
    }


    /**
     * 终极加固：智能终端僵尸连接回收器 (Reaper)
     * 每 10 分钟执行一次，强制收割超期 30 分钟未交互的连接
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedRate = 600000)
    public void reaperSessions() {
        long now = System.currentTimeMillis();
        long timeout = 30 * 60 * 1000;
        sshContexts.forEach((sid, ctx) -> {
            if (now - ctx.getLastActiveTime() > timeout) {
                log.warn("🛡️ Connection Reaper: Harvesting idle session: {}", sid);
                try {
                    if (ctx.channel != null) ctx.channel.disconnect();
                    if (ctx.session != null) ctx.session.disconnect();
                    if (ctx.wsSession != null && ctx.wsSession.isOpen()) {
                        ctx.wsSession.close(new CloseStatus(4000, "Idle Timeout"));
                    }
                } catch (Exception e) {}
                sshContexts.remove(sid);
            }
        });
    }
}
