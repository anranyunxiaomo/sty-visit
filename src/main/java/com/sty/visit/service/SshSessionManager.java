package com.sty.visit.service;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.sty.visit.util.DirectSocketFactory;
import com.sty.visit.config.SshProperties;
import com.sty.visit.util.JwtUtil;
import com.sty.visit.util.StyVisitConstants;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import javax.annotation.PreDestroy;
import javax.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 终极加固：SSH 会话池管理器
 */
@Service
public class SshSessionManager {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SshSessionManager.class);

    @Value("${visit.ssh-host:127.0.0.1}")
    private String defaultHost;

    @Value("${visit.ssh-port:22}")
    private int defaultPort;

    @Value("${visit.ssh-strict-host-key-check:no}")
    private String strictHostKeyCheck;

    @Autowired
    private SshProperties sshProperties;

    private final Map<String, PooledSession> sessionPool = new ConcurrentHashMap<>();
    private final Map<String, String> tokenToSessionKey = new ConcurrentHashMap<>(); // [NEW] Token 粘滞映射
    
    // 限流容器：IP -> {失败次数, 锁定到期时间}
    private final Map<String, Integer> authFailures = new ConcurrentHashMap<>();
    private final Map<String, Long> clientLockouts = new ConcurrentHashMap<>();

    // [TELEMETRY] 实时运行指标容器
    private final java.util.concurrent.atomic.AtomicLong totalConnectTime = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicInteger connectionCount = new java.util.concurrent.atomic.AtomicInteger(0);
    private final java.util.concurrent.atomic.AtomicInteger errorCount = new java.util.concurrent.atomic.AtomicInteger(0);

    @Autowired
    private com.sty.visit.service.CryptoService cryptoService;

    @Autowired
    private com.sty.visit.service.AuditService auditService;
    
    private static class PooledSession {
        private Session session;
        private long lastActive;
        public PooledSession(Session s) { this.session = s; this.lastActive = System.currentTimeMillis(); }
        public void refresh() { this.lastActive = System.currentTimeMillis(); }

        public Session getSession() { return session; }
        public long getLastActive() { return lastActive; }
    }

    @PreDestroy
    public void cleanup() {
        log.info("SshSessionManager: 正在清理所有 SSH 会话...");
        sessionPool.values().forEach(ps -> {
            try { if (ps.session != null && ps.session.isConnected()) ps.session.disconnect(); } catch (Exception ignored) {}
        });
        sessionPool.clear();
    }

    private final Map<String, ReentrantLock> connectionLocks = new ConcurrentHashMap<>();

    /**
     * [SshGuardian] 自动巡检机制：实现连接池的配置化主动收割与自愈
     */
    @Scheduled(fixedRateString = "${visit.ssh.check-interval:60000}")
    public void sessionReaper() {
        long now = System.currentTimeMillis();
        
        // 1. 清理过期 IP 锁定
        clientLockouts.entrySet().removeIf(entry -> now > entry.getValue());
        authFailures.keySet().removeIf(ip -> !clientLockouts.containsKey(ip));

        // 2. 基于配置的物理收割
        java.util.Set<String> keysToRemove = new java.util.HashSet<>();
        sessionPool.entrySet().forEach(entry -> {
            PooledSession ps = entry.getValue();
            boolean isTimeout = now - ps.lastActive > sshProperties.getMaxIdleTime();
            boolean isDead = !ps.session.isConnected();
            
            if (isTimeout || isDead) {
                log.info("🛡️ SshGuardian: 准备回收失效会话: {} (原因: {})", entry.getKey(), isDead ? "链路断开" : "连接超时");
                try { ps.session.disconnect(); } catch (Exception ignored) {}
                keysToRemove.add(entry.getKey());
            }
        });

        if (!keysToRemove.isEmpty()) {
            keysToRemove.forEach(key -> {
                sessionPool.remove(key);
                connectionLocks.remove(key);
                tokenToSessionKey.values().removeIf(v -> v.equals(key));
            });
        }
    }

    public void invalidateSession(String sessionKey) {
        if (sessionKey == null) return;
        PooledSession ps = sessionPool.remove(sessionKey);
        connectionLocks.remove(sessionKey); // [FIX] 同步清理细粒度锁池，防止长期运行内存泄漏
        if (ps != null) {
            log.info("🛡️ 物理销毁会话: {}", sessionKey);
            try { ps.session.disconnect(); } catch (Exception ignored) {}
        }
    }

    public String getSessionKey(HttpServletRequest request) {
        String authHeader = request.getHeader(StyVisitConstants.AUTH_HEADER);
        if (authHeader == null) return null;
        String token = authHeader.startsWith(StyVisitConstants.AUTH_PREFIX) ? authHeader.substring(StyVisitConstants.AUTH_PREFIX.length()) : authHeader;

        // 1. 提取原始报文
        String hostRaw = getHeader(request, "X-SSH-Host", defaultHost);
        String userRaw = getHeader(request, "X-SSH-User", null);
        String portRaw = getHeader(request, "X-SSH-Port", String.valueOf(defaultPort));

        if (hostRaw == null || userRaw == null) {
            // H5 粘滞路径：优先通过 Token 寻找已有指纹
            return tokenToSessionKey.get(token);
        }

        // 2. 物理还原核心要素
        String host = decryptPrimitive(hostRaw, token);
        String user = decryptPrimitive(userRaw, token);
        String port = decryptPrimitive(portRaw, token);

        if (host == null || user == null) {
            log.warn("🛡️ SSH 指纹提取失败: Host 缺失? {}, User 缺失? {}", (host == null), (user == null));
            return null;
        }

        // 3. 计算稳定指纹：仅包含物理目标，不含动态加固头
        String fingerPrint = String.format("STABLE_V2:%s@%s:%s", user, host, port);
        String sessionKey = DigestUtils.md5DigestAsHex(fingerPrint.getBytes(StandardCharsets.UTF_8));
        
        // 自动注入粘滞映射
        tokenToSessionKey.put(token, sessionKey);
        return sessionKey;
    }

    public Session getSessionByKey(String sessionKey) {
        if (sessionKey == null) return null;
        PooledSession ps = sessionPool.get(sessionKey);
        if (ps != null && ps.session.isConnected()) {
            ps.refresh();
            return ps.session;
        }
        return null;
    }

    public Session getSession(HttpServletRequest request) throws Exception {
        String ip = request.getRemoteAddr();
        
        // 1. 防御性检查：客户端 IP 锁定状态
        if (clientLockouts.containsKey(ip)) {
            if (System.currentTimeMillis() < clientLockouts.get(ip)) {
                throw new SecurityException("由于多次 SSH 认证失败，该 IP 已被暂时封禁 (冷却中)");
            }
            clientLockouts.remove(ip);
            authFailures.remove(ip);
        }

        String sessionKey = getSessionKey(request);
        if (sessionKey == null) {
            String host = request.getHeader("X-SSH-Host");
            String user = request.getHeader("X-SSH-User");
            throw new SecurityException("SSH 凭证缺失或格式错误 [Host=" + (host != null) + ", User=" + (user != null) + "]");
        }

        ReentrantLock lock = connectionLocks.computeIfAbsent(sessionKey, k -> new java.util.concurrent.locks.ReentrantLock());
        lock.lock();
        try {
            PooledSession ps = sessionPool.get(sessionKey);
            if (ps != null) {
                boolean isAlive = false;
                try {
                    // [PHYSICAL PROBE] 物理嗅探：发送空包探测链路真实存活状态，防止协议层僵死
                    if (ps.session.isConnected()) {
                        ps.session.sendKeepAliveMsg(); 
                        isAlive = true;
                    }
                } catch (Exception e) {
                    log.warn("🛡️ 会话 [{}] 主动探测失败，正在丢弃...", sessionKey);
                }

                if (isAlive) {
                    ps.refresh();
                    return ps.session;
                } else {
                    invalidateSession(sessionKey);
                }
            }

            String hostRaw = getHeader(request, "X-SSH-Host", defaultHost);
            String portRaw = getHeader(request, "X-SSH-Port", String.valueOf(defaultPort));
            String userRaw = getHeader(request, "X-SSH-User", null);
            String pwdRaw = getHeader(request, "X-SSH-Pwd", null);
            String keyRaw = getHeader(request, "X-SSH-Key", null);

            String authHeader = request.getHeader(StyVisitConstants.AUTH_HEADER);
            String token = authHeader;
            if (authHeader != null && authHeader.startsWith(StyVisitConstants.AUTH_PREFIX)) {
                token = authHeader.substring(StyVisitConstants.AUTH_PREFIX.length());
            }

            String host = decryptPrimitive(hostRaw, token);
            String user = decryptPrimitive(userRaw, token);
            String portStr = decryptPrimitive(portRaw, token);
            String pwd = decryptPrimitive(pwdRaw, token);
            String keyEncoded = decryptPrimitive(keyRaw, token);

            int port = 22;
            try {
                if (portStr != null && !portStr.trim().isEmpty()) {
                    port = Integer.parseInt(portStr.trim());
                }
            } catch (Exception pe) {
                log.warn("🛡️ 无效的端口 [{}]，默认降级至 22", portStr);
            }

            // [PHYSICAL FIX] 静默诊断算子：预检信息暂存，仅在真正失败时输出，防止干扰用户
            String diagnosticInfo = "未执行预检";
            try (java.net.Socket testSocket = new java.net.Socket(java.net.Proxy.NO_PROXY)) {
                long startTime = System.currentTimeMillis();
                testSocket.connect(new java.net.InetSocketAddress(host, port), 3000); 
                diagnosticInfo = "TCP 握手成功 (" + (System.currentTimeMillis() - startTime) + "ms)";
                log.debug("🛡️ 内网连通性诊断: {}", diagnosticInfo);
            } catch (Exception e) {
                diagnosticInfo = "TCP 握手失败: " + e.getMessage();
                // 此时不打印 ERROR 日志，交给 JSch 尝试最后一次机会
            }
 
            // 物理清洗：保留密码原始内容，不进行 trim，防止密码本就包含空格
            String finalPwd = pwd;
            if ("null".equals(finalPwd)) finalPwd = null;
            
            String finalHost = (host != null) ? host.trim() : null;
            String finalUser = (user != null) ? user.trim() : null;
            char[] pwdChars = finalPwd != null ? finalPwd.toCharArray() : null;
            
            // [DEBUG LOG] 物理排障：打印解密后的元数据（严禁打印明文密码）
            log.info("🛡️ 鉴权状态诊断: 主机=[{}], 用户=[{}], 密码长度=[{}], 包含私钥=[{}], 载有令牌=[{}]", 
                finalHost, finalUser, (finalPwd != null ? finalPwd.length() : 0), 
                (keyEncoded != null && !keyEncoded.isEmpty()), (token != null));

            boolean hasPrivateKey = (keyEncoded != null && !keyEncoded.isEmpty());
            
            try {
                JSch jsch = new JSch();
                if (keyEncoded != null && !keyEncoded.isEmpty()) {
                    try {
                        byte[] keyBytes;
                        if (keyEncoded.contains("-----BEGIN")) {
                            // PEM 格式私钥：直接加载
                            keyBytes = keyEncoded.getBytes(StandardCharsets.UTF_8);
                        } else {
                            // 兼容性尝试：Base64 格式私钥
                            keyBytes = java.util.Base64.getDecoder().decode(keyEncoded.replace("\n", "").replace("\r", ""));
                        }
                        jsch.addIdentity("client_key", keyBytes, null, null);
                        log.info("🛡️ 身份凭据已加载: 正在使用自定义 SSH 私钥");
                    } catch (Exception ke) {
                        log.warn("🛡️ 身份凭据加载失败: {}", ke.getMessage());
                    }
                }
                
                // [INTRANET DIAGNOSTIC] 物理层连通性预检
                log.info("🛡️ 内网连通性诊断: 探测目标 {}:{}", finalHost, port);
                try (java.net.Socket testSocket = new java.net.Socket(java.net.Proxy.NO_PROXY)) {
                    long startTime = System.currentTimeMillis();
                    testSocket.connect(new java.net.InetSocketAddress(finalHost, port), 5000); 
                    log.info("🛡️ 内网连通性诊断: TCP 握手成功 ({}ms)，网络畅通。问题可能出在 SSH 协议层。", (System.currentTimeMillis() - startTime));
                } catch (Exception e) {
                    log.error("🛡️ 内网连通性诊断: TCP 握手失败: {}。这是网络路由或物理阻断问题。", e.getMessage());
                }

                // [PHYSICAL FIX] 三重握手重试：对抗物理链路波动的鲁棒性设计
                int maxRetries = 3;
                Exception lastEx = null;
                for (int i = 0; i < maxRetries; i++) {
                    SshUserInfo userInfo = null;
                    try {
                        Session session = jsch.getSession(finalUser, finalHost, port);
                        if (pwdChars != null) {
                            String pwdStr = new String(pwdChars);
                            session.setPassword(pwdStr);
                            userInfo = new SshUserInfo(pwdStr);
                            session.setUserInfo(userInfo);
                        }
                        
                        // [CORE FIX] 注入物理直连工厂，绕过系统 SOCKS 代理
                        session.setSocketFactory(new DirectSocketFactory(sshProperties.getConnectTimeout()));
                        
                        Properties config = new Properties();
                        config.put("StrictHostKeyChecking", strictHostKeyCheck);
                        config.put("kex", "ecdh-sha2-nistp256,ecdh-sha2-nistp384,ecdh-sha2-nistp521,diffie-hellman-group-exchange-sha256,diffie-hellman-group14-sha256,diffie-hellman-group14-sha1,diffie-hellman-group-exchange-sha1,diffie-hellman-group1-sha1");
                        config.put("server_host_key", "ssh-ed25519,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,rsa-sha2-512,rsa-sha2-256,ssh-rsa,ssh-dss");
                        config.put("PubkeyAcceptedAlgorithms", "ssh-ed25519,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,rsa-sha2-512,rsa-sha2-256,ssh-rsa");
                        
                        String authMethods = hasPrivateKey ? "publickey,password,keyboard-interactive" : "password,keyboard-interactive";
                        config.put("PreferredAuthentications", authMethods);
                        config.put("compression.s2c", "zlib,none");
                        config.put("compression.c2s", "zlib,none");
                        config.put("MaxAuthTries", "5");
                        config.put("UseDNS", "no");
                        
                        session.setConfig(config);
                        session.setServerAliveInterval(30000);
                        session.setServerAliveCountMax(3);
                        
                        long start = System.currentTimeMillis();
                        session.connect(sshProperties.getConnectTimeout());
                        totalConnectTime.addAndGet(System.currentTimeMillis() - start);
                        connectionCount.incrementAndGet();
                        
                        sessionPool.put(sessionKey, new PooledSession(session));
                        authFailures.remove(ip);
                        log.info("🛡️ 加密隧道已建立: 指纹索引为: {}", sessionKey);
                        return session;
                    } catch (Exception e) {
                        lastEx = e;
                        log.warn("🛡️ SSH 连接重试 [{}/{}]: {}", i + 1, maxRetries, e.getMessage());
                        if (e.getMessage() != null && (e.getMessage().contains("closed by foreign host") || e.getMessage().contains("Auth fail"))) {
                            try { Thread.sleep(800); } catch (InterruptedException ignored) {}
                            continue;
                        }
                        break;
                    } finally {
                        if (userInfo != null) userInfo.clear();
                    }
                }
                throw lastEx != null ? lastEx : new Exception("SSH 隧道建立失败");
            } catch (Exception e) {
                errorCount.incrementAndGet();
                log.error("🛡️ SSH 鉴权错误 [{}@{}:{}]: {}", user, host, port, e.getMessage());
                e.printStackTrace(); // 物理输出堆栈，精确定位 Auth Fail 深度坐标
                // 2. 失败计分：累加 IP 失败计数
                int count = authFailures.getOrDefault(ip, 0) + 1;
                authFailures.put(ip, count);
                if (count >= 50) { // 阈值提升至 50，放宽调试冗余
                    clientLockouts.put(ip, System.currentTimeMillis() + 15 * 60 * 1000); // 锁定 15 分钟
                    auditService.log(ip, StyVisitConstants.ACTION_SSH_LOGIN, "触发爆破防御，自动封禁 IP", "安全警报");
                }
                throw e;
            } finally {
                if (pwdChars != null) java.util.Arrays.fill(pwdChars, ' ');
            }
        } finally {
            lock.unlock();
        }
    }

    private String decryptPrimitive(String val, String token) {
        if (val == null || val.isEmpty()) return val;
        
        String pwd = val;

        boolean isDynamic = false;
        if (pwd.startsWith("PROTECTED:AES:")) {
            pwd = pwd.substring(14);
            isDynamic = true;
        }

        // 1. 动态层解密
        if (isDynamic && token != null) {
            try {
                pwd = cryptoService.decryptWithToken(pwd, token);
            } catch (Exception e) {
                log.error("🛡️ 动态解密失败: {}", e.getMessage());
                throw new SecurityException("凭证解密失败，会话可能已过期，请尝试重新登录。");
            }
        }
        
        // 2. 静态层解密
        if (pwd != null && pwd.contains(":")) {
            try {
                String staticDecrypted = cryptoService.decryptStatic(pwd);
                if (staticDecrypted != null) {
                    pwd = staticDecrypted;
                }
            } catch (Exception ignored) {
                // 如果刚好是个带冒号的明文，保持原样
            }
        }

        return pwd;
    }

    private String getHeader(HttpServletRequest request, String name, String defaultValue) {
        String val = request.getHeader(name);
        if (val == null || val.trim().isEmpty() || "null".equals(val)) return defaultValue;
        return val.trim(); // 物理返回 trim 后的数值
    }

    private static class SshUserInfo implements com.jcraft.jsch.UserInfo, com.jcraft.jsch.UIKeyboardInteractive {
        private char[] password;
        public SshUserInfo(String password) { this.password = password != null ? password.toCharArray() : null; }
        @Override public String getPassphrase() { return null; }
        @Override public String getPassword() { return password != null ? new String(password) : null; }
        @Override public boolean promptPassword(String message) { return true; }
        @Override public boolean promptPassphrase(String message) { return true; }
        @Override public boolean promptYesNo(String message) { return true; }
        @Override public void showMessage(String message) { log.debug("SSH UserInfo: {}", message); }
        @Override public String[] promptKeyboardInteractive(String destination, String name, String instruction, String[] prompt, boolean[] echo) {
            String[] response = new String[prompt.length];
            for (int i = 0; i < prompt.length; i++) {
                if (prompt[i] != null) response[i] = password != null ? new String(password) : null;
            }
            return response;
        }
        // [PHYSICAL ERASE] 提供物理粉碎接口
        public void clear() {
            if (password != null) java.util.Arrays.fill(password, ' ');
        }
    }
}
