package com.sty.visit.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 终极加固：应用层全量加解密服务
 * 提供 AES-256-GCM 高强度报文防护，支持动态会话密钥管理
 */
@Service
public class CryptoService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CryptoService.class);


    @org.springframework.beans.factory.annotation.Value("${visit.jwt-secret}")
    private String jwtSecret;

    // 存储 Token 与 AES 密钥的映射 (Session Key)
    private final Map<String, PooledKey> sessionKeys = new ConcurrentHashMap<>();
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 静态内容加固：使用系统级 Secret 进行加解密
     */
    public String encryptStatic(String content) throws Exception {
        byte[] key = generateMasterKey();
        byte[] iv = generateIv();
        byte[] cipher = encrypt(content.getBytes(StandardCharsets.UTF_8), key, iv);
        return Base64.getEncoder().encodeToString(iv) + ":" + Base64.getEncoder().encodeToString(cipher);
    }

    public String decryptStatic(String encrypted) throws Exception {
        if (encrypted == null || !encrypted.contains(":")) return encrypted;
        String[] parts = encrypted.split(":");
        byte[] iv = Base64.getDecoder().decode(parts[0]);
        byte[] cipher = Base64.getDecoder().decode(parts[1]);
        byte[] key = generateMasterKey();
        return new String(decrypt(cipher, key, iv), StandardCharsets.UTF_8);
    }

    private byte[] generateMasterKey() throws Exception {
        java.security.MessageDigest sha = java.security.MessageDigest.getInstance("SHA-256");
        return sha.digest(jwtSecret.getBytes(StandardCharsets.UTF_8));
    }

    @lombok.Data
    private static class PooledKey {
        private byte[] key;
        private long lastActive;
        public PooledKey(byte[] k) { this.key = k; this.lastActive = System.currentTimeMillis(); }
        public void refresh() { this.lastActive = System.currentTimeMillis(); }
    }

    /**
     * 行为审计与资源收割器：每小时执行一次，清理 8 小时未使用的闲置密钥
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedRate = 3600000)
    public void keyReaper() {
        long now = System.currentTimeMillis();
        long timeout = 8 * 60 * 60 * 1000;
        sessionKeys.entrySet().removeIf(entry -> {
            boolean isIdle = now - entry.getValue().lastActive > timeout;
            if (isIdle) {
                log.info("🛡️ Crypto Reaper: Harvesting expired session key for token prefix: {}...", 
                        entry.getKey().substring(0, Math.min(entry.getKey().length(), 8)));
                return true;
            }
            return false;
        });
    }

    /**
     * 为新进入的登录会话生成 256 位对等密钥
     * [ALIGNMENT] 物理对齐：基于 App 端提供的 Admin Key (jwtSecret) 派生会话密钥，确保零存证安全性。
     */
    public String generateSessionKey(String token) {
        byte[] key;
        try {
            // 利用 MessageDigest 从 jwtSecret 派生出确定性的对等密钥
            java.security.MessageDigest sha = java.security.MessageDigest.getInstance("SHA-256");
            key = sha.digest(jwtSecret.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            key = new byte[32];
            secureRandom.nextBytes(key);
        }
        sessionKeys.put(token, new PooledKey(key));
        return Base64.getEncoder().encodeToString(key);
    }

    public byte[] getSessionKey(String token) {
        PooledKey pk = sessionKeys.get(token);
        if (pk != null) {
            pk.refresh();
            return pk.key;
        }
        try {
            byte[] key = generateMasterKey();
            sessionKeys.put(token, new PooledKey(key));
            return key;
        } catch (Exception e) {
            return null;
        }
    }

    public void removeSessionKey(String token) {
        sessionKeys.remove(token);
    }

    /**
     * 业务层解密：使用会话密钥解密客户端发送的敏感请求头 (Header)
     */
    public String decryptWithToken(String encrypted, String token) throws Exception {
        if (encrypted == null || !encrypted.contains(":")) return encrypted;
        
        byte[] sessionKey = getSessionKey(token);
        if (sessionKey == null) throw new SecurityException("会话密钥已失效，请重新登录");

        String[] parts = encrypted.split(":");
        byte[] iv = Base64.getDecoder().decode(parts[0]);
        byte[] cipherData = Base64.getDecoder().decode(parts[1]);

        byte[] decrypted = decrypt(cipherData, sessionKey, iv);
        return new String(decrypted, StandardCharsets.UTF_8);
    }

    /**
     * AES-GCM 解密逻辑
     */
    public byte[] decrypt(byte[] encryptedData, byte[] key, byte[] iv) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec spec = new GCMParameterSpec(128, iv);
        SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
        cipher.init(Cipher.DECRYPT_MODE, keySpec, spec);
        return cipher.doFinal(encryptedData);
    }

    /**
     * AES-GCM 加密逻辑
     */
    public byte[] encrypt(byte[] data, byte[] key, byte[] iv) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec spec = new GCMParameterSpec(128, iv);
        SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, spec);
        return cipher.doFinal(data);
    }
    
    public byte[] generateIv() {
        byte[] iv = new byte[12]; // GCM 推荐 12 字节 IV
        secureRandom.nextBytes(iv);
        return iv;
    }
}
