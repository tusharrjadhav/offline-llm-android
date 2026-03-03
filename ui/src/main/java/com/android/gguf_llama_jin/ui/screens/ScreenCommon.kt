package com.android.gguf_llama_jin.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.gguf_llama_jin.core.ModelRuntime
import kotlin.math.roundToInt

@Composable
fun RuntimeFilterRow(selected: ModelRuntime, onSelect: (ModelRuntime) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        AssistChip(
            onClick = { onSelect(ModelRuntime.LLAMA_CPP_GGUF) },
            label = { Text("GGUF") },
            trailingIcon = {
                if (selected == ModelRuntime.LLAMA_CPP_GGUF) {
                    Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            }
        )
        AssistChip(
            onClick = { onSelect(ModelRuntime.ONNX_RUNTIME) },
            label = { Text("ONNX") },
            trailingIcon = {
                if (selected == ModelRuntime.ONNX_RUNTIME) {
                    Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            }
        )
    }
}

@Composable
fun RuntimeChip(runtime: ModelRuntime) {
    AssistChip(onClick = {}, label = { Text(runtime.label()) })
}

fun ModelRuntime.label(): String = when (this) {
    ModelRuntime.LLAMA_CPP_GGUF -> "GGUF"
    ModelRuntime.ONNX_RUNTIME -> "ONNX"
}

fun formatFileSize(sizeBytes: Long): String {
    if (sizeBytes <= 0L) return "Unknown"
    val mb = sizeBytes / (1024.0 * 1024.0)
    if (mb < 1024.0) return "${mb.roundToInt()} MB"
    val gb = mb / 1024.0
    return String.format("%.2f GB", gb)
}
