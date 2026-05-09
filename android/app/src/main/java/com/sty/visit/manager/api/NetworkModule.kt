package com.sty.visit.manager.api

import android.util.Log
import com.sty.visit.manager.util.AppConstants
import com.sty.visit.manager.util.SessionManager
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object NetworkModule {
    private var apiService: ApiService? = null

    fun getApiService(baseUrl: String): ApiService {
        if (apiService == null || SessionManager.serverUrl != baseUrl) {
            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.HEADERS
            }

            // [PHYSICAL FIX] 显式拦截器：确保凭证注入的原子性与可见性
            val authInterceptor = object : Interceptor {
                override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
                    val original = chain.request()
                    val requestBuilder = original.newBuilder()
                        .header("Content-Type", "application/json")
                    
                    // 1. 注入 JWT 令牌
                    SessionManager.authToken?.let {
                        if (it.isNotEmpty()) {
                            requestBuilder.header(AppConstants.HEADER_AUTH, AppConstants.AUTH_PREFIX + it)
                        }
                    }
                    
                    // 2. 注入 SSH 隧道元数据
                    SessionManager.currentBookmark?.let { b ->
                        requestBuilder.header("X-SSH-Host", b.host)
                        requestBuilder.header("X-SSH-Port", b.port)
                        requestBuilder.header("X-SSH-User", b.user)
                        
                        b.pwd?.let { if (it.isNotEmpty()) requestBuilder.header("X-SSH-Pwd", it) }
                        b.key?.let { if (it.isNotEmpty()) requestBuilder.header("X-SSH-Key", it) }
                        
                        // [DEBUG] 物理输出日志，辅助确认 Headers 是否真实外发
                        Log.d("VisitNet", "🚀 Header Injection: Host=${b.host}, User=${b.user}, HasPwd=${!b.pwd.isNullOrEmpty()}")
                    } ?: run {
                        Log.w("VisitNet", "⚠️ Header Injection Warning: No current bookmark set!")
                    }

                    return chain.proceed(requestBuilder.build())
                }
            }

            val client = OkHttpClient.Builder()
                .addInterceptor(logging)
                .addInterceptor(authInterceptor)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()

            val retrofit = Retrofit.Builder()
                .baseUrl(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/")
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()

            apiService = retrofit.create(ApiService::class.java)
            SessionManager.serverUrl = baseUrl
        }
        return apiService!!
    }
}
