package com.android.gguf_llama_jin.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.gguf_llama_jin.ui.viewmodel.AppViewModel

@Composable
fun SettingsScreen(
    viewModel: AppViewModel,
    padding: PaddingValues,
    onBrowseModels: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val snapshot = state.capabilitySnapshot

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Text("Privacy & Behavior", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
        item { ToggleRow("Store chat history", state.historyEnabled) { viewModel.toggleHistory(it) } }
        item { ToggleRow("Wi-Fi only downloads", state.wifiOnly) { viewModel.toggleWifiOnly(it) } }
        item { ToggleRow("Telemetry (opt-in)", state.telemetryEnabled) { viewModel.toggleTelemetry(it) } }
        item { ToggleRow("Allow web search in chat", state.webSearchAllowed) { viewModel.toggleWebSearchAllowed(it) } }
        item { HorizontalDivider() }

        item { Text("Preferred Runtime", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
        item {
            RuntimeFilterRow(
                selected = state.preferredRuntime,
                onSelect = { viewModel.setPreferredRuntime(it) }
            )
        }

        item { Text("Downloaded Models", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
        if (state.installed.isEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Text("No models installed.", modifier = Modifier.padding(12.dp))
                }
            }
        } else {
            items(state.installed) { model ->
                Card {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(model.id, fontWeight = FontWeight.SemiBold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                RuntimeChip(model.runtime)
                                AssistChip(onClick = {}, label = { Text(model.variant) })
                            }
                        }
                        val isDefault = state.selectedModelByRuntime[model.runtime] == model.id
                        if (isDefault) {
                            FilledTonalIconButton(onClick = {}) {
                                Icon(Icons.Filled.CheckCircle, contentDescription = "Selected model")
                            }
                        } else {
                            OutlinedButton(onClick = { viewModel.chooseDefaultModel(model.runtime, model.id) }) {
                                Text("Set Default")
                            }
                        }
                    }
                }
            }
        }

        item {
            Button(onClick = onBrowseModels, modifier = Modifier.fillMaxWidth()) {
                Text("Browse & Download Models")
            }
        }

        item { HorizontalDivider() }
        item { Text("Device Info", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("RAM: ${snapshot?.totalRamMb ?: 0} MB")
                    Text("Available RAM: ${snapshot?.availableRamMb ?: 0} MB")
                    Text("Free storage: ${(snapshot?.freeStorageBytes ?: 0L) / (1024 * 1024 * 1024)} GB")
                    Text("Recommended max model: ${(snapshot?.recommendedMaxModelBytes ?: 0L) / (1024 * 1024 * 1024)} GB")
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title)
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}
