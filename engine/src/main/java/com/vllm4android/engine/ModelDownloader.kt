package com.vllm4android.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Downloads a `.litertlm` model from a Hugging Face repo on first run and
 * caches it on the device. Subsequent launches reuse the local file.
 */
class ModelDownloader(
    private val cacheDir: File,
    private val client: OkHttpClient = defaultClient,
) {
    data class Spec(
        val repoId: String,           // e.g. "litert-community/gemma-4-E2B-it-litert-lm"
        val fileName: String,         // e.g. "gemma-4-E2B-it.litertlm"
        val revision: String = "main",
    ) {
        val url: String get() =
            "https://huggingface.co/$repoId/resolve/$revision/$fileName"
    }

    sealed interface Progress {
        data class Downloading(val bytesRead: Long, val totalBytes: Long) : Progress
        data class Done(val file: File) : Progress
    }

    fun localFile(spec: Spec): File = File(cacheDir, spec.fileName)

    fun isCached(spec: Spec): Boolean = localFile(spec).exists()

    fun download(spec: Spec): Flow<Progress> = flow {
        val target = localFile(spec)
        if (target.exists()) {
            emit(Progress.Done(target))
            return@flow
        }
        cacheDir.mkdirs()
        val tmp = File(cacheDir, "${spec.fileName}.part")

        val response = client.newCall(Request.Builder().url(spec.url).build()).execute()
        if (!response.isSuccessful) {
            response.close()
            error("Download failed: HTTP ${response.code}")
        }
        val body = response.body ?: error("Empty response body")
        val total = body.contentLength()

        body.byteStream().use { input ->
            tmp.outputStream().use { output ->
                val buffer = ByteArray(1 shl 16)
                var read: Int
                var totalRead = 0L
                while (input.read(buffer).also { read = it } > 0) {
                    output.write(buffer, 0, read)
                    totalRead += read
                    emit(Progress.Downloading(totalRead, total))
                }
            }
        }
        if (!tmp.renameTo(target)) error("Could not move ${tmp.path} to ${target.path}")
        emit(Progress.Done(target))
    }.flowOn(Dispatchers.IO)

    companion object {
        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS) // long downloads
            .build()

        val GEMMA_4_E2B_IT = Spec(
            repoId = "litert-community/gemma-4-E2B-it-litert-lm",
            fileName = "gemma-4-E2B-it.litertlm",
        )
    }
}
