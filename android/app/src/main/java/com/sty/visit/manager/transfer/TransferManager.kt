package com.sty.visit.manager.transfer

import android.content.Context
import android.os.Environment
import com.sty.visit.manager.api.ApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import android.media.MediaScannerConnection
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody

class TransferManager(private val context: Context, private val apiServiceProvider: () -> ApiService) {

    data class TransferTask(
        val name: String,
        val type: String, // "UPLOAD" or "DOWNLOAD"
        var progress: Int = 0,
        var status: String = "PENDING",
        val downloadToken: String = "",
        var errorMessage: String? = null,
        var targetPath: String = "",
        val startOffset: Long = 0,
        val id: Long = System.currentTimeMillis()
    )

    private val _tasks = MutableStateFlow<List<TransferTask>>(emptyList())
    val tasks: StateFlow<List<TransferTask>> = _tasks

    private val queue = ConcurrentLinkedQueue<TransferTask>()
    private var isWorking = false

    suspend fun enqueueDownload(fileName: String, downloadToken: String, offset: Long = 0) {
        val folder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val targetPath = File(folder, fileName).absolutePath
        val task = TransferTask(name = fileName, type = "DOWNLOAD", targetPath = targetPath, downloadToken = downloadToken, startOffset = offset)
        val current = _tasks.value.toMutableList()
        current.add(task)
        _tasks.value = current
        queue.add(task)
        if (!isWorking) startProcessor()
    }

    private suspend fun startProcessor() {
        isWorking = true
        while (queue.isNotEmpty()) {
            val task = queue.poll() ?: break
            processTask(task)
        }
        isWorking = false
    }

    private suspend fun processTask(task: TransferTask) {
        updateTaskStatus(task, "DOWNLOADING")
        try {
            // [ALIGNMENT] 物理构造 Range 头以支持断点续传逻辑
            val rangeHeader = if (task.startOffset > 0) "bytes=${task.startOffset}-" else null
            val response = apiServiceProvider().downloadFile(task.downloadToken, rangeHeader)
            if (response.isSuccessful && response.body() != null) {
                saveToFile(task, response.body()!!)
                updateTaskStatus(task, "COMPLETED")
            } else {
                updateTaskStatus(task, "ERROR", "服务器端响应失败或为空")
            }
        } catch (e: Exception) { updateTaskStatus(task, "ERROR", e.message) }
    }

    private suspend fun saveToFile(task: TransferTask, body: ResponseBody) {
        withContext(Dispatchers.IO) {
            val folder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!folder.exists()) folder.mkdirs()
            val file = File(folder, task.name)
            try {
                val inputStream = body.byteStream()
                // [ALIGNMENT] 如果有 offset，使用 append 模式写入
                val outputStream = FileOutputStream(file, task.startOffset > 0)
                val data = ByteArray(8192)
                var read: Int
                var totalRead: Long = task.startOffset
                val fileSize = body.contentLength() + task.startOffset
                while (inputStream.read(data).also { read = it } != -1) {
                    outputStream.write(data, 0, read)
                    totalRead += read
                    if (fileSize > 0) {
                        val progress = (totalRead * 100 / fileSize).toInt()
                        if (progress != task.progress) {
                            task.progress = progress
                            _tasks.value = _tasks.value.toList()
                        }
                    }
                }
                outputStream.flush(); outputStream.close(); inputStream.close()
                MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
            } catch (e: Exception) { if (file.exists()) file.delete(); throw e }
        }
    }

    private fun updateTaskStatus(task: TransferTask, status: String, error: String? = null) {
        task.status = status
        if (error != null) task.errorMessage = error
        _tasks.value = _tasks.value.toList()
        
        if (status == "COMPLETED" || status == "ERROR") {
            syncTaskToBackend(task)
        }
    }

    private fun syncTaskToBackend(task: TransferTask) {
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            try {
                val map = mapOf<String, Any>(
                    "id" to task.id,
                    "name" to task.name,
                    "type" to task.type,
                    "status" to task.status,
                    "progress" to task.progress,
                    "targetPath" to task.targetPath,
                    "errorMessage" to (task.errorMessage ?: ""),
                    "timestamp" to java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                )
                apiServiceProvider().saveTransfer(map)
            } catch (e: Exception) {
                // Ignore sync errors
            }
        }
    }

    suspend fun uploadFile(uri: android.net.Uri, remotePath: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return@withContext Pair(false, "无法读取文件 (InputStream null)")
            val fileBytes = inputStream.readBytes()
            if (fileBytes.isEmpty()) return@withContext Pair(false, "选中的文件为空 (0 字节)")
            val fileName = getFileName(uri)
            
            val mediaType = "application/octet-stream".toMediaTypeOrNull()
            val requestFile = fileBytes.toRequestBody(mediaType)
            
            val remoteFile = if (remotePath.endsWith("/")) remotePath + fileName else "$remotePath/$fileName"
            val task = TransferTask(name = fileName, type = "UPLOAD", targetPath = remoteFile, status = "UPLOADING")
            _tasks.value = _tasks.value + task
            
            val progressRequestBody = ProgressRequestBody(requestFile) { progress ->
                if (progress != task.progress && progress <= 100) {
                    task.progress = progress
                    _tasks.value = _tasks.value.toList()
                }
            }
            
            val body = MultipartBody.Part.createFormData("file", fileName, progressRequestBody)
            val pathBody = remotePath.toRequestBody("text/plain".toMediaTypeOrNull())
            
            val response = apiServiceProvider().uploadFile(body, pathBody)
            if (response.isSuccessful && response.body()?.code == 200) {
                updateTaskStatus(task, "COMPLETED")
                task.progress = 100
                Pair(true, "上传成功")
            } else {
                val errorMsg = response.body()?.message ?: "服务器返回异常状态"
                updateTaskStatus(task, "ERROR", errorMsg)
                Pair(false, "上传失败: $errorMsg")
            }
        } catch (e: Exception) { 
            // 查找刚才创建的正在上传的任务并将其标记为错误
            _tasks.value.lastOrNull { it.type == "UPLOAD" && it.status == "UPLOADING" }?.let { updateTaskStatus(it, "ERROR", e.message) }
            Pair(false, "网络异常: ${e.message}") 
        }
    }

    private fun getFileName(uri: android.net.Uri): String {
        var name = "upload_${System.currentTimeMillis()}"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index != -1) name = cursor.getString(index)
            }
        }
        return name
    }

    fun clearFinishedTasks() {
        val filtered = _tasks.value.filter { it.status == "PENDING" || it.status == "DOWNLOADING" || it.status == "UPLOADING" }
        _tasks.value = filtered
    }
}
