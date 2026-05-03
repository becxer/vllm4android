package com.vllm4android.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.vllm4android.app.service.LlmServerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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
                    ServerScreen(
                        binderFlow = binderState,
                        onStart = { startServer() },
                        onStop = { stopServer() },
                    )
                }
            }
        }
    }

    private fun startServer() {
        val intent = Intent(this, LlmServerService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
        // Kick off the pipeline once bound.
        binderState.value?.start()
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

@Composable
private fun ServerScreen(
    binderFlow: StateFlow<LlmServerService.LocalBinder?>,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val binder by binderFlow.collectAsState()
    val state = binder?.state?.collectAsState()?.value ?: LlmServerService.State.Idle

    var lastTapped by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = "vllm4android", style = MaterialTheme.typography.headlineMedium)
        Text(text = "Gemma 4 E2B IT · LiteRT-LM · OpenAI-compatible server")

        Spacer(Modifier.height(8.dp))

        when (val s = state) {
            is LlmServerService.State.Idle -> Text("Idle")
            is LlmServerService.State.Downloading -> {
                val total = s.totalBytes.takeIf { it > 0 }
                Text("Downloading model…")
                if (total != null) {
                    LinearProgressIndicator(
                        progress = { s.bytesRead.toFloat() / total.toFloat() },
                        modifier = Modifier.fillMaxSize().height(6.dp),
                    )
                } else {
                    LinearProgressIndicator()
                }
                Text("${s.bytesRead / 1_000_000} MB / ${(total ?: 0) / 1_000_000} MB")
            }
            is LlmServerService.State.Loading -> {
                Text("Loading model into memory… (this can take ~10s)")
                LinearProgressIndicator()
            }
            is LlmServerService.State.Running -> {
                Text("Serving on http://0.0.0.0:${s.port}")
                Text("Try: curl http://<device-ip>:${s.port}/v1/chat/completions \\\n  -d '{\"model\":\"gemma-4-E2B-it\",\"messages\":[{\"role\":\"user\",\"content\":\"Hello\"}]}'")
            }
            is LlmServerService.State.Error -> Text("Error: ${s.message}")
        }

        Button(onClick = { onStart(); lastTapped = "start" }) { Text("Start server") }
        Button(onClick = { onStop(); lastTapped = "stop" }) { Text("Stop server") }
    }
}
