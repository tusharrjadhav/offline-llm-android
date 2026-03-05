package com.android.gguf_llama_jin.data.network

import com.android.gguf_llama_jin.core.Constants
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.util.Locale

object AppHttpClient {
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    val client: HttpClient by lazy {
        HttpClient(CIO) {
            install(ContentNegotiation) {
                json(json)
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 30_000
            }
            install(DefaultRequest) {
                headers.append(HttpHeaders.Accept, "application/json")
                headers.append(HttpHeaders.UserAgent, Constants.USER_AGENT)
                headers.append(HttpHeaders.AcceptLanguage, Locale.US.toLanguageTag())
            }
        }
    }
}
