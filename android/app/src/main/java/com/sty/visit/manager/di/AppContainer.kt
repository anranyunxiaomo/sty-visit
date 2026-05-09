package com.sty.visit.manager.di

import android.content.Context
import android.util.Base64
import android.util.Log
import com.sty.visit.manager.api.ApiService
import com.sty.visit.manager.util.AppConstants
import com.sty.visit.manager.util.SessionManager
import com.sty.visit.manager.util.CryptoUtils
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * 终极依赖容器 (AppContainer)
 * 已加固：AES-GCM 物理脱壳引擎 (v3 - 兼容性增强版)
 */
class AppContainer(private val context: Context) {

    private var apiService: ApiService = createDummyService()

    private fun getBaseClient(): OkHttpClient {
        val logging = okhttp3.logging.HttpLoggingInterceptor { message ->
            Log.d("VisitNet", "📡 $message")
        }.apply {
            level = okhttp3.logging.HttpLoggingInterceptor.Level.HEADERS
        }

        val trafficInterceptor = object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val original = chain.request()
                val requestBuilder = original.newBuilder()
                
                SessionManager.authToken?.let {
                    if (it.isNotEmpty()) {
                        requestBuilder.header(AppConstants.HEADER_AUTH, AppConstants.AUTH_PREFIX + it)
                    }
                }
                
                SessionManager.currentBookmark?.let { b ->
                    requestBuilder.header("X-SSH-Host", b.host)
                    requestBuilder.header("X-SSH-Port", b.port)
                    requestBuilder.header("X-SSH-User", b.user)
                    b.pwd?.let { if (it.isNotEmpty()) requestBuilder.header("X-SSH-Pwd", it) }
                    b.key?.let { if (it.isNotEmpty()) requestBuilder.header("X-SSH-Key", it) }
                }

                val response = chain.proceed(requestBuilder.build())
                if (response.code == 101) return response // [CRITICAL] 跳过协议升级响应，防止破坏 WebSocket 握手
                
                val responseBody = response.body ?: return response
                
                // 提前读取流内容到内存，防止后续读取时流已关闭
                val rawBytes = responseBody.bytes()
                
                val isEncrypted = response.header("X-Visit-Encrypted") == "true"
                val iv = response.header("X-Visit-IV")
                val sessionKey = SessionManager.sessionKey

                if (isEncrypted && !iv.isNullOrEmpty() && !sessionKey.isNullOrEmpty()) {
                    try {
                        var actualEncryptedData = rawBytes

                        // [CRITICAL FIX] 兼容性补丁：处理 Jackson 可能导致的二次 Base64 包装
                        if (rawBytes.size > 2 && rawBytes[0] == '"'.code.toByte() && rawBytes[rawBytes.size - 1] == '"'.code.toByte()) {
                            val base64Content = String(rawBytes, 1, rawBytes.size - 2)
                            actualEncryptedData = Base64.decode(base64Content, Base64.DEFAULT)
                        }

                        val decryptedBytes = CryptoUtils.decryptRaw(actualEncryptedData, sessionKey, iv)
                        val decryptedBody = decryptedBytes.toResponseBody("application/json".toMediaTypeOrNull())
                        
                        Log.d("VisitNet", "🛡️ [Interceptor] 响应报文脱壳成功 (v5)")
                        
                        return response.newBuilder()
                            .body(decryptedBody)
                            .header("Content-Type", "application/json")
                            .build()
                    } catch (e: Exception) {
                        Log.e("VisitNet", "❌ 响应报文脱壳失败 (BAD_DECRYPT): ${e.message}")
                    }
                }

                // [ALIGNMENT] 物理对齐：即使解密失败，也必须使用原始字节重建响应返回，防止流关闭崩溃
                val originalBody = rawBytes.toResponseBody(responseBody.contentType())
                return response.newBuilder().body(originalBody).build()
            }
        }

        return OkHttpClient.Builder()
            .addInterceptor(logging)
            .addInterceptor(trafficInterceptor)
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    fun initRetrofit(baseUrl: String, ignoreSsl: Boolean = false, client: OkHttpClient? = null) {
        val actualClient = client ?: getBaseClient()
        val retrofit = Retrofit.Builder()
            .baseUrl(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/")
            .client(actualClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        val service = retrofit.create(ApiService::class.java)
        this.apiService = service
        SessionManager.serverUrl = baseUrl
    }

    fun getApiService(): ApiService = apiService

    fun getOkHttpClient(): OkHttpClient = getBaseClient()

    private fun createDummyService(): ApiService {
        return Retrofit.Builder()
            .baseUrl("http://localhost/")
            .client(getBaseClient())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}
