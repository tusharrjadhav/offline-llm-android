# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Android application for running GGUF language models fully on-device using `llama.cpp` through a JNI bridge. Supports optional web search grounding via the Tavily API.

## Module Structure

The project is organized into 4 Gradle modules with a clear dependency hierarchy:

| Module | Type | Purpose | Dependencies |
|--------|------|---------|--------------|
| `:inference` | Android library | Core inference interfaces, data layer, web search | None |
| `:llama_cpp` | Android library | Native JNI bridge to llama.cpp | `:inference` |
| `:ui` | Android library | Compose UI, ViewModels, navigation | `:inference`, `:llama_cpp` |
| `:app` | Android application | Application shell, permissions | `:ui` |

## Build Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Build with Tavily API key for web search
./gradlew assembleDebug -PtavilyApiKey=YOUR_KEY

# Run unit tests
./gradlew test

# Run single unit test class
./gradlew test --tests "DeviceHeuristicsTest"

# Run instrumentation tests
./gradlew connectedAndroidTest

# Clean build
./gradlew clean
```

## Key Build Configuration

- **minSdk**: 31 (Android 12+)
- **compileSdk**: 36 with minor API level 1
- **targetSdk**: 36
- **Java**: VERSION_11
- **Kotlin**: 2.0.21
- **AGP**: 9.0.1
- **ABI Filter**: arm64-v8a only (see llama_cpp/build.gradle.kts)
- **NDK Optimization**: `-O3` applied to both release and debug builds

## Architecture Patterns

### Dependency Injection Pattern
The app uses manual constructor injection with a provider pattern in MainActivity.kt:
- `InferenceSessionManager` receives a `Set<LlmRuntimeBridge>` allowing multiple runtime backends
- The llama_cpp runtime bridge implements `LlmRuntimeBridge` interface

### JNI Bridge Pattern
Native communication follows a polling-based streaming design:
1. `LlmNativeBridgeImpl.loadModel()` → returns model handle
2. `startSession()` → returns session handle
3. `generate()` → calls `nativeGenerateStart()`, then polls via `nativePollToken()`
4. Kotlin Flow emits `TokenChunk` objects incrementally for UI streaming

### UDF Architecture
UI layer uses Unidirectional Data Flow with Compose:
- `AppViewModel` exposes `AppUiState` via `StateFlow`
- `AppUiContract` defines Actions (user intents) and Effects (one-time events)
- Screens observe state and dispatch actions through the contract

## Key Files and Responsibilities

| File | Responsibility |
|------|----------------|
| `ui/viewmodel/AppViewModel.kt` | Main ViewModel coordinating UI, inference, and web search |
| `inference/InferenceSessionManager.kt` | Manages model/session lifecycle, delegates to runtime bridges |
| `llama_cpp/.../LlmNativeBridgeImpl.kt` | JNI bridge implementation for llama.cpp native calls |
| `llama_cpp/src/main/cpp/gguf_jni.cpp` | Native C++ implementation with llama.cpp integration |
| `inference/.../HfCatalogApi.kt` | Hugging Face API client for model discovery |
| `core/Constants.kt` | HF_API_BASE, default quant (Q4_K_M), file paths |

## Native Development

The llama.cpp native library is built via CMake:
- **CMake file**: `llama_cpp/src/main/cpp/CMakeLists.txt`
- **Source**: `llama_cpp/src/main/cpp/gguf_jni.cpp`
- **Third-party**: `llama_cpp/src/main/cpp/third_party/llama.cpp` (submodule)

CMake disables all optional llama.cpp features (tests, tools, examples, server) for minimal binary size.

## Web Search Configuration

Tavily API key is injected at build time via `buildConfigField` in `inference/build.gradle.kts`:
- Property name: `tavilyApiKey`
- Runtime access: `BuildConfig.TAVILY_API_KEY`
- When missing, falls back to `MockWebSearchProvider`

## Model Storage

Downloaded GGUF models are stored in app files directory:
- **Models directory**: `files/models/`
- **Registry file**: `files/model_registry.json`
- **Settings**: SharedPreferences named `gguf_settings`

## Testing

Unit tests are located in `app/src/test/` and follow the naming convention of testing one class each:
- `DeviceHeuristicsTest.kt` - RAM/storage validation logic
- `InferenceSessionManagerTest.kt` - Session lifecycle management
- `TavilyWebSearchProviderTest.kt` - Web search API integration
