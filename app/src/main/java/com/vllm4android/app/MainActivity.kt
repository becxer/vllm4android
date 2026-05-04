package com.vllm4android.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.vllm4android.app.service.LlmServerService
import com.vllm4android.app.ui.ServerScreen
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private val binderState = MutableStateFlow<LlmServerService.LocalBinder?>(null)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binderState.value = service as? LlmServerService.LocalBinder
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            binderState.value = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val binder by binderState.collectAsState()
                    val state = binder?.state?.collectAsState()?.value
                        ?: LlmServerService.State.Idle
                    ServerScreen(
                        state = state,
                        onStart = ::startServer,
                        onStop = ::stopServer,
                    )
                }
            }
        }
    }

    /**
     * Starts the foreground service (which kicks off the download → load
     * → serve pipeline in [LlmServerService.onStartCommand]) and binds to
     * it so the UI can observe state. There is no separate "begin work"
     * call — that was racy because [bindService] returns before
     * [onServiceConnected] fires.
     */
    private fun startServer() {
        val intent = Intent(this, LlmServerService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun stopServer() {
        binderState.value?.stop()
        runCatching { unbindService(connection) }
    }

    override fun onDestroy() {
        runCatching { unbindService(connection) }
        super.onDestroy()
    }
}
