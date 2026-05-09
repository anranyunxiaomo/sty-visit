package com.sty.visit.util;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Date;

@Component
public class JwtUtil {

    @Value("${visit.jwt-secret}")
    private String secret;

    @javax.annotation.PostConstruct
    public void init() {
        // 终极安全合规审计：强制高熵秘钥
        if (secret == null || secret.length() < 24 || 
            !secret.matches(".*[a-z].*") || !secret.matches(".*[A-Z].*") || 
            !secret.matches(".*[0-9].*") || !secret.matches(".*[!@#$%^&*()_+=\\-\\[\\]{}|;:,.<>?].*")) {
            throw new IllegalStateException("\n[CORE SECURITY ERROR] JWT 秘钥配置不符合工业级安全规范！\n" +
                    "要求：长度至少 24 位，且必须包含大小写字母、数字及特殊符号。\n" +
                    "请立即更新 application.yml 中的 visit.jwt-secret 配置。");
        }
    }

    private static final long EXPIRE_TIME = 8 * 60 * 60 * 1000; // 缩短至 8小时，平衡体验与安全

    public String createToken(String username) {
        Algorithm algorithm = Algorithm.HMAC256(secret);
        return JWT.create()
                .withIssuer("visit_manager_pro")
                .withClaim("username", username)
                .withExpiresAt(new Date(System.currentTimeMillis() + EXPIRE_TIME))
                .sign(algorithm);
    }

    public boolean verify(String token) {
        try {
            com.auth0.jwt.interfaces.DecodedJWT jwt = com.auth0.jwt.JWT.require(com.auth0.jwt.algorithms.Algorithm.HMAC256(secret))
                    .withIssuer("visit_manager_pro")
                    .acceptLeeway(5) // 允许 5 秒的时钟误差
                    .build()
                    .verify(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static String decodeExternalValue(String encoded) {
        if (encoded == null) return null;
        try { return new String(java.util.Base64.getDecoder().decode(encoded), java.nio.charset.StandardCharsets.UTF_8); }
        catch (Exception e) { return encoded; }
    }

    /**
     * [NEW] 内存安全解码：返回 char[] 以便于即时擦除
     */
    public static char[] decodeToChars(String encoded) {
        if (encoded == null) return new char[0];
        try {
            byte[] bytes = java.util.Base64.getDecoder().decode(encoded);
            java.nio.charset.CharsetDecoder decoder = java.nio.charset.StandardCharsets.UTF_8.newDecoder();
            java.nio.ByteBuffer byteBuffer = java.nio.ByteBuffer.wrap(bytes);
            java.nio.CharBuffer charBuffer = java.nio.CharBuffer.allocate(bytes.length);
            
            decoder.decode(byteBuffer, charBuffer, true);
            decoder.flush(charBuffer);
            
            char[] result = new char[charBuffer.position()];
            charBuffer.flip();
            charBuffer.get(result);
            
            // 物理擦除中间敏感字节
            java.util.Arrays.fill(bytes, (byte) 0);
            return result;
        } catch (Exception e) {
            return encoded.toCharArray();
        }
    }
}
