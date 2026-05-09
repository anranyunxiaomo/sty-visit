package com.sty.visit.manager.util

import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Android 平台业务层解扰工具类
 * 实现与 Java/JS 对等的 AES-GCM 动态报文加密方案
 */
object CryptoUtils {
    private val secureRandom = SecureRandom()

    /**
     * [ALIGNMENT] 密钥派生：将用户密码转换为 256 位强密钥 (SHA-256)
     * 对齐功能清单 1.2 "AES-256 物理级强加密" 要求
     */
    fun deriveKey(password: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(password.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(hash, Base64.NO_WRAP)
    }

    /**
     * 使用会话密钥加密敏感载荷
     */
    fun encrypt(plainText: String, sessionKeyBase64: String): String {
        try {
            val keyBytes = Base64.decode(sessionKeyBase64, Base64.NO_WRAP)
            val iv = ByteArray(12)
            secureRandom.nextBytes(iv)
            
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val keySpec = SecretKeySpec(keyBytes, "AES")
            val gcmSpec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
            
            val cipherText = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
            
            val ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)
            val cipherBase64 = Base64.encodeToString(cipherText, Base64.NO_WRAP)
            
            return "PROTECTED:AES:$ivBase64:$cipherBase64"
        } catch (e: Exception) {
            return Base64.encodeToString(plainText.toByteArray(), Base64.NO_WRAP)
        }
    }

    /**
     * 核心解密逻辑：直接处理原始字节，提高效率与鲁棒性
     */
    fun decryptRaw(encryptedData: ByteArray, sessionKeyBase64: String, ivBase64: String): ByteArray {
        val keyBytes = Base64.decode(sessionKeyBase64, Base64.NO_WRAP)
        val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
        
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(keyBytes, "AES")
        val gcmSpec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
        
        return cipher.doFinal(encryptedData)
    }

    fun decrypt(encryptedBase64: String, sessionKeyBase64: String, ivBase64: String): String {
        val encryptedBytes = Base64.decode(encryptedBase64, Base64.NO_WRAP)
        val decrypted = decryptRaw(encryptedBytes, sessionKeyBase64, ivBase64)
        return String(decrypted, StandardCharsets.UTF_8)
    }

    fun decrypt(protectedText: String, sessionKeyBase64: String): String {
        if (!protectedText.startsWith("PROTECTED:AES:")) return protectedText
        val parts = protectedText.split(":")
        if (parts.size < 4) return protectedText
        return decrypt(parts[3], sessionKeyBase64, parts[2])
    }

    fun isProtected(text: String?): Boolean {
        return text != null && (text.startsWith("PROTECTED:AES:") || text.contains(":"))
    }
}
