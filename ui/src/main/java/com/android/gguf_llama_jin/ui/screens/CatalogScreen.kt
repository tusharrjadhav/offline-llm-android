package com.android.gguf_llama_jin.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.gguf_llama_jin.core.ModelRuntime
import com.android.gguf_llama_jin.data.catalog.CatalogModel
import com.android.gguf_llama_jin.data.catalog.ModelVariant
import com.android.gguf_llama_jin.data.download.DownloadState
import com.android.gguf_llama_jin.ui.viewmodel.AppUiState
import com.android.gguf_llama_jin.ui.viewmodel.AppViewModel
import kotlin.math.roundToInt

@Composable
fun CatalogScreen(viewModel: AppViewModel, padding: PaddingValues) {
    val state by viewModel.uiState.collectAsState()
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val query = searchQuery.trim()
    val catalog = state.catalog.filter {
        query.isBlank() ||
            it.displayName.contains(query, true) ||
            it.repo.contains(query, true) ||
            it.tags.any { t -> t.contains(query, true) }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Search, contentDescription = null)
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.weight(1f),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            decorationBox = { inner ->
                                if (searchQuery.isBlank()) {
                                    Text("Search models", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                inner()
                            }
                        )
                        if (searchQuery.isNotBlank()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Filled.Close, contentDescription = "Clear")
                            }
                        }
                    }
                }
                FilledIconButton(onClick = { viewModel.refreshCatalog() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                }
            }
        }

        item {
            RuntimeFilterRow(
                selected = state.selectedRuntimeFilter,
                onSelect = { viewModel.setCatalogRuntimeFilter(it) }
            )
        }

        if (state.loadingCatalog) {
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
        }

        val installedByRuntime = state.installed.groupBy { it.runtime }
        installedByRuntime.forEach { (runtime, models) ->
            if (models.isNotEmpty()) {
                item {
                    Text(
                        "Installed (${runtime.label()})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                items(models) { installed ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                RuntimeBadge(runtime)
                                Text(installed.id, fontWeight = FontWeight.Bold)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    AssistChip(onClick = {}, label = { Text(installed.variant) })
                                }
                                Text("Size: ${formatFileSize(installed.sizeBytes)}")
                            }
                            val isDefault = state.selectedModelByRuntime[runtime] == installed.id
                            if (isDefault) {
                                FilledTonalIconButton(onClick = {}) {
                                    Icon(Icons.Filled.CheckCircle, contentDescription = "Selected")
                                }
                            } else {
                                OutlinedButton(onClick = { viewModel.chooseDefaultModel(runtime, installed.id) }) {
                                    Text("Set Default")
                                }
                            }
                        }
                    }
                }
            }
        }

        item { HorizontalDivider() }

        item {
            Text("Model Catalog", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }

        if (catalog.isEmpty() && !state.loadingCatalog) {
            item {
                Text("No models found", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        items(catalog) { model ->
            CatalogModelCard(model = model, state = state, viewModel = viewModel)
        }
    }
}

@Composable
private fun CatalogModelCard(model: CatalogModel, state: AppUiState, viewModel: AppViewModel) {
    val variants = modelVariantsForUi(model)
    val variantKey = "${model.runtime.name}:${model.id}"
    val selectedVariant = state.selectedVariantByModel[variantKey] ?: variants.firstOrNull()?.variantId.orEmpty()
    val selected = variants.firstOrNull { it.variantId == selectedVariant } ?: variants.firstOrNull() ?: return
    val task = state.downloads.values.firstOrNull { it.request.modelId == model.id && it.request.runtime == model.runtime }
    val isInstalled = state.installed.any { it.id == model.id && it.runtime == model.runtime && it.variant == selected.variantId }
    val message = state.modelMessages["${model.runtime.name}:${model.id}"]

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RuntimeBadge(model.runtime)
            Text(model.displayName, fontWeight = FontWeight.Bold)
            Text("Params: ${model.paramsApprox} | Tier: ${model.recommendedTier}")
            Text(model.repo)
            if (model.runtime == ModelRuntime.ONNX) {
                val fileCount = selected.metadata["files"] ?: selected.downloadFiles.size.toString()
                Text("Package files: $fileCount")
            }

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                variants.forEach { variant ->
                    AssistChip(
                        onClick = { viewModel.setVariant(model, variant.variantId) },
                        label = { Text(if (selected.variantId == variant.variantId) "${variant.variantId} *" else variant.variantId) }
                    )
                }
            }

            Text("Size: ${formatFileSize(selected.sizeBytes)}")

            if (task != null && task.state != DownloadState.FAILED && task.state != DownloadState.CANCELED) {
                Text("Status: ${task.state}")
                val progress = if (task.totalBytes > 0) (task.downloadedBytes.toFloat() / task.totalBytes.toFloat()).coerceIn(0f, 1f) else null
                if (progress != null) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text("${(progress * 100f).roundToInt()}%")
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            if (!message.isNullOrBlank()) {
                Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (isInstalled) {
                    FilledTonalIconButton(onClick = {}) { Icon(Icons.Filled.CheckCircle, contentDescription = "Downloaded") }
                } else {
                    FilledIconButton(
                        onClick = { viewModel.startDownload(model) },
                        enabled = task == null || task.state == DownloadState.FAILED || task.state == DownloadState.CANCELED
                    ) {
                        Icon(Icons.Filled.Download, contentDescription = "Download")
                    }
                }

                val isDefault = state.selectedModelByRuntime[model.runtime] == model.id
                OutlinedButton(onClick = { viewModel.chooseDefaultModel(model.runtime, model.id) }, enabled = isInstalled && !isDefault) {
                    Text(if (isDefault) "Selected" else "Set Default")
                }
            }

            if (task != null && task.state != DownloadState.COMPLETED && task.state != DownloadState.CANCELED && task.state != DownloadState.FAILED) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalIconButton(onClick = { viewModel.pauseDownload(model) }, enabled = task.state == DownloadState.DOWNLOADING) {
                        Icon(Icons.Filled.Pause, contentDescription = "Pause")
                    }
                    FilledTonalIconButton(onClick = { viewModel.resumeDownload(model) }, enabled = task.state == DownloadState.PAUSED) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Resume")
                    }
                    FilledTonalIconButton(onClick = { viewModel.stopDownload(model) }) {
                        Icon(Icons.Filled.Stop, contentDescription = "Stop")
                    }
                }
            }
        }
    }
}

@Composable
private fun RuntimeBadge(runtime: ModelRuntime) {
    val containerColor = when (runtime) {
        ModelRuntime.LLAMA_CPP_GGUF -> MaterialTheme.colorScheme.tertiaryContainer
        ModelRuntime.ONNX -> MaterialTheme.colorScheme.secondaryContainer
    }
    val contentColor = when (runtime) {
        ModelRuntime.LLAMA_CPP_GGUF -> MaterialTheme.colorScheme.onTertiaryContainer
        ModelRuntime.ONNX -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = containerColor
    ) {
        Text(
            text = runtime.label(),
            color = contentColor,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

private fun modelVariantsForUi(model: CatalogModel): List<ModelVariant> {
    val invalidTags = setOf("", "UNKNOWN", "ERROR", "N/A", "NA", "NULL", "NONE")
    val seen = mutableSetOf<String>()
    val cleaned = model.variants.mapNotNull { variant ->
        val raw = variant.variantId.trim()
        val normalized = raw.uppercase()
        if (normalized in invalidTags) return@mapNotNull null
        if (model.runtime == ModelRuntime.LLAMA_CPP_GGUF && normalized == "GGUF") return@mapNotNull null
        if (!seen.add(normalized)) return@mapNotNull null
        variant
    }
    if (cleaned.isNotEmpty()) return cleaned

    val fallback = model.variants.firstOrNull {
        val normalized = it.variantId.trim().uppercase()
        normalized.isNotBlank() && normalized !in invalidTags
    }
    return fallback?.let(::listOf) ?: emptyList()
}
