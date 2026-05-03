package com.vllm4android.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vllm4android.app.service.LlmServerService

@Composable
fun ServerScreen(
    state: LlmServerService.State,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("vllm4android", style = MaterialTheme.typography.headlineMedium)
        Text("Gemma 4 E2B IT · LiteRT-LM · OpenAI-compatible server")

        Spacer(Modifier.height(8.dp))

        StatusBlock(state)

        Button(onClick = onStart) { Text("Start server") }
        Button(onClick = onStop) { Text("Stop server") }
    }
}

@Composable
private fun StatusBlock(state: LlmServerService.State) {
    when (state) {
        is LlmServerService.State.Idle -> Text("Idle")

        is LlmServerService.State.Downloading -> {
            Text("Downloading model…")
            val total = state.totalBytes.takeIf { it > 0 }
            if (total != null) {
                LinearProgressIndicator(
                    progress = { state.bytesRead.toFloat() / total.toFloat() },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                )
                Text("${state.bytesRead / 1_000_000} MB / ${total / 1_000_000} MB")
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }

        is LlmServerService.State.Loading -> {
            Text("Loading model into memory… (this can take ~10s)")
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        is LlmServerService.State.Running -> {
            Text("Serving on http://0.0.0.0:${state.port}")
            Text(
                "Try:\ncurl http://<device-ip>:${state.port}/v1/chat/completions \\\n" +
                    "  -H 'Content-Type: application/json' \\\n" +
                    "  -d '{\"model\":\"gemma-4-E2B-it\"," +
                    "\"messages\":[{\"role\":\"user\",\"content\":\"Hello\"}]}'",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        is LlmServerService.State.Error -> Text("Error: ${state.message}")
    }
}
