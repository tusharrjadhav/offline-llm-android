package com.android.gguf_llama_jin.data.catalog.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class HfModelDetailsDto(
    @SerialName("id")
    val id: String? = null,
    @SerialName("tags")
    val tags: List<String> = emptyList(),
    @SerialName("license")
    val license: String? = null,
    @SerialName("siblings")
    val siblings: List<HfSiblingDto> = emptyList()
)

@Serializable
data class HfSiblingDto(
    @SerialName("rfilename")
    val fileName: String? = null,
    @SerialName("size")
    val size: Long? = null,
    @SerialName("lfs")
    val lfs: HfLfsDto? = null
)

@Serializable
data class HfLfsDto(
    @SerialName("size")
    val size: Long? = null,
    @SerialName("sha256")
    val sha256: String? = null
)
