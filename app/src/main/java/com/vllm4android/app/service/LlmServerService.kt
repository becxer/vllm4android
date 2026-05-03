package com.vllm4android.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.vllm4android.engine.LlmEngine
import com.vllm4android.engine.ModelDownloader
import com.vllm4android.server.OpenAiServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the model download → engine load → HTTP
 * server pipeline. The pipeline kicks off automatically on
 * [onStartCommand], so the UI only has to start/stop the service — there
 * is no separate "begin work" RPC and therefore no race between binding
 * and starting.
 *
 * State is exposed via [State] through a local [Binder] so the UI can
 * observe progress.
 */
class LlmServerService : Service() {

    sealed interface State {
        data object Idle : State
        data class Downloading(val bytesRead: Long, val totalBytes: Long) : State
        data object Loading : State
        data class Running(val port: Int) : State
        data class Error(val message: String) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var engine: LlmEngine? = null
    private var server: OpenAiServer? = null
    private var pipelineJob: Job? = null

    private val _state = MutableStateFlow<State>(State.Idle)

    private val binder = LocalBinder()
    inner class LocalBinder : android.os.Binder() {
        val state: StateFlow<State> get() = _state.asStateFlow()
        fun stop() = this@LlmServerService.stopPipeline()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Starting…"))
        startPipeline()
        return START_STICKY
    }

    private fun startPipeline() {
        if (pipelineJob?.isActive == true) return
        pipelineJob = scope.launch {
            try {
                val modelFile = downloadModel()
                loadEngineAndServe(modelFile.absolutePath)
            } catch (t: Throwable) {
                _state.value = State.Error(t.message ?: t.toString())
                updateNotification("Error: ${t.message}")
            }
        }
    }

    private suspend fun downloadModel(): java.io.File {
        val downloader = ModelDownloader(cacheDir = filesDir)
        val spec = ModelDownloader.GEMMA_4_E2B_IT
        downloader.download(spec).collect { p ->
            when (p) {
                is ModelDownloader.Progress.Downloading -> {
                    _state.value = State.Downloading(p.bytesRead, p.totalBytes)
                    updateNotification(
                        "Downloading model… ${humanBytes(p.bytesRead)} / ${humanBytes(p.totalBytes)}",
                    )
                }
                is ModelDownloader.Progress.Done -> Unit
            }
        }
        return downloader.localFile(spec)
    }

    private suspend fun loadEngineAndServe(modelPath: String) {
        _state.value = State.Loading
        updateNotification("Loading model into memory…")

        val newEngine = LlmEngine(modelPath = modelPath).also { it.initialize() }
        engine = newEngine

        val newServer = OpenAiServer(
            engine = newEngine,
            modelId = MODEL_ID,
            port = SERVER_PORT,
        ).also { it.start() }
        server = newServer

        _state.value = State.Running(SERVER_PORT)
        updateNotification("Serving on port $SERVER_PORT")
    }

    private fun stopPipeline() {
        scope.launch {
            pipelineJob?.cancel()
            server?.stop()
            engine?.close()
            server = null
            engine = null
            _state.value = State.Idle
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        pipelineJob?.cancel()
        server?.stop()
        engine?.close()
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "LLM Server", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("vllm4android")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun humanBytes(bytes: Long): String {
        if (bytes <= 0) return "?"
        val units = arrayOf("B", "KiB", "MiB", "GiB")
        var value = bytes.toDouble()
        var i = 0
        while (value >= 1024 && i < units.lastIndex) { value /= 1024; i++ }
        return "%.1f %s".format(value, units[i])
    }

    companion object {
        const val MODEL_ID = "gemma-4-E2B-it"
        const val SERVER_PORT = 8080
        private const val CHANNEL_ID = "llm-server"
        private const val NOTIFICATION_ID = 1
    }
}
