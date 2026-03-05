package com.android.gguf_llama_jin.data.catalog.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class HfModelListItemDto(
    @SerialName("id")
    val id: String? = null
)
