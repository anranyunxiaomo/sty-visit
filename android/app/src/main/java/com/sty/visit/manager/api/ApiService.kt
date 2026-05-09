package com.sty.visit.manager.api

import com.google.gson.annotations.SerializedName
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*
import okhttp3.RequestBody

/**
 * 核心 API 契约 (Interface Pattern)
 * 移除所有冗余 Header 定义，由 NetworkModule 拦截器统一注入。
 */
interface ApiService {
    @POST("/api/diag/report")
    suspend fun reportIssue(@Body body: DiagnosticRequest): Response<Result<String>>

    @POST("/api/auth/login")
    suspend fun login(@Body body: Map<String, String>): Response<Result<LoginResponse>>

    @GET("/api/file/list")
    suspend fun listFiles(@Query("path") path: String): Response<Result<List<FileInfo>>>

    @GET("/api/audit/logs")
    suspend fun getLogs(): Response<Result<List<AuditLog>>>

    @GET("/api/file/content")
    suspend fun getFileContent(@Query("path") path: String): Response<Result<String>>

    @POST("/api/file/save")
    suspend fun saveFile(@Body body: Map<String, String>): Response<Result<String>>

    @Streaming
    @GET("/api/file/download")
    suspend fun downloadFile(
        @Query("token") downloadToken: String,
        @Header("Range") range: String? = null
    ): Response<ResponseBody>

    @PUT("/api/file/rename")
    suspend fun renameFile(@Query("oldPath") oldPath: String, @Query("newName") newName: String): Response<Result<String>>

    @DELETE("/api/file")
    suspend fun deleteFile(@Query("path") path: String): Response<Result<String>>

    @POST("/api/file/bulk-delete")
    suspend fun bulkDelete(@Body paths: List<String>): Response<Result<String>>

    @GET("/api/config/h5/status")
    suspend fun getH5Status(): Response<Result<Map<String, Any>>>

    @POST("/api/config/h5/status")
    suspend fun setH5Status(@Query("enabled") enabled: Boolean): Response<Result<String>>

    @POST("/api/config/wipe")
    suspend fun wipeData(@Body body: Map<String, String>): Response<Result<String>>

    @POST("/api/config/h5/toggle")
    suspend fun toggleH5(@Body body: Map<String, Boolean>): Response<Result<String>>

    @GET("/api/config/audit/retention")
    suspend fun getAuditRetention(): Response<Result<Map<String, Any>>>

    @POST("/api/config/audit/retention")
    suspend fun updateAuditRetention(@Body body: Map<String, Int>): Response<Result<String>>

    @GET("/api/snippets")
    suspend fun getSnippets(): Response<Result<List<Snippet>>>

    @POST("/api/snippets")
    suspend fun saveSnippets(@Body snippets: List<Snippet>): Response<Result<String>>

    @GET("/api/bookmarks")
    suspend fun getBookmarks(): Response<Result<List<Bookmark>>>

    @POST("/api/bookmarks")
    suspend fun saveBookmark(@Body bookmark: Bookmark): Response<Result<String>>

    @DELETE("/api/bookmarks")
    suspend fun deleteBookmark(@Query("id") id: String): Response<Result<String>>

    @GET("/api/monitor/stats")
    suspend fun getStats(): Response<Result<SystemStats>>

    @GET("/api/file/token")
    suspend fun getDownloadToken(@Query("path") path: String): Response<Result<Map<String, String>>>

    @Multipart
    @POST("/api/file/upload")
    suspend fun uploadFile(
        @Part file: MultipartBody.Part,
        @Part("path") path: RequestBody
    ): Response<Result<String>>

    @GET("/api/transfers")
    suspend fun getTransfers(): Response<Result<List<Map<String, Any>>>>

    @POST("/api/transfers")
    suspend fun saveTransfer(@Body transfer: Map<String, Any>): Response<Result<String>>

    @DELETE("/api/transfers")
    suspend fun clearTransfers(): Response<Result<String>>
}

data class LoginResponse(val token: String, val sessionKey: String) : java.io.Serializable

data class FileInfo(
    val name: String,
    val path: String,
    @SerializedName("dir") val isDir: Boolean,
    val size: Long,
    val updateTime: String
) : java.io.Serializable

data class AuditLog(
    val timestamp: String,
    val ip: String,
    val action: String,
    val detail: String,
    val status: String,
    val riskLevel: String
) : java.io.Serializable

data class Snippet(
    val name: String,
    val command: String,
    val category: String
) : java.io.Serializable

data class SystemStats(
    val cpu: Double,
    val mem: Double,
    val load: Double,
    val uptime: String?,
    val disks: List<DiskStat>?
) : java.io.Serializable

data class DiskStat(
    val mount: String,
    val usage: Double
) : java.io.Serializable

data class Bookmark(
    val id: String? = null,
    val name: String,
    val host: String,
    val port: String,
    val user: String,
    val pwd: String? = null,
    val key: String? = null,
    @SerializedName("isKeyAuth") val isKeyAuth: Boolean = false
) : java.io.Serializable

data class Result<T>(
    val code: Int,
    val message: String?,
    val data: T?
) : java.io.Serializable {
    companion object {
        fun <T> success(data: T): Result<T> = Result(200, "success", data)
    }
}

data class DiagnosticRequest(
    val type: String,
    val detail: String,
    val platform: String = "ANDROID"
) : java.io.Serializable
