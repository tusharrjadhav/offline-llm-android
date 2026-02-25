# GGUF Llama JNI (Android On-Device LLM)

Android app for running GGUF language models fully on-device using `llama.cpp` through a JNI bridge.

## Architecture Overview

![GGUF & On-Device AI Overview](docs/gguf-on-device-ai-overview.png)

This visual summarizes the architecture implemented in this project:

- GGUF format as the local model format (weights + metadata).
- Android Kotlin UI/orchestrator connected to native C++ via JNI.
- `llama.cpp` driving local token generation.
- Optional web-search grounding on top of local inference.

## What this app does

- Discovers compatible GGUF instruct/chat models.
- Downloads selected model files to app-local storage.
- Runs local inference (streaming response tokens) using native C++.
- Supports optional web-search grounding via app-level tool orchestration.

---

## Where models are fetched from

Model catalog and files are fetched from Hugging Face:

- API base: `https://huggingface.co/api`
- Catalog search: GGUF instruct/chat repos
- Model files: `.gguf` variants from repository `siblings`

Relevant code:

- `app/src/main/java/com/android/gguf_llama_jin/data/catalog/HfCatalogApi.kt`
- `app/src/main/java/com/android/gguf_llama_jin/core/Constants.kt`

Downloaded models are stored under app files directory in a models folder:

- `files/models/...`

Registry/settings are maintained locally (no cloud model storage in-app).

---

## How we interact with the model

High-level flow:

1. User sends prompt from Compose UI.
2. `AppViewModel` validates selected installed model.
3. ViewModel calls `InferenceSessionManager`.
4. `InferenceSessionManager` ensures model/session is loaded.
5. `LlmNativeBridgeImpl` invokes native JNI functions.
6. Native side streams tokens back via polling.
7. UI updates message incrementally (streaming text).

Key files:

- `app/src/main/java/com/android/gguf_llama_jin/ui/viewmodel/AppViewModel.kt`
- `app/src/main/java/com/android/gguf_llama_jin/inference/InferenceSessionManager.kt`
- `app/src/main/java/com/android/gguf_llama_jin/inference/LlmNativeBridgeImpl.kt`
- `app/src/main/cpp/gguf_jni.cpp`

---

## How C++ native (JNI) helps

Why JNI is used:

- `llama.cpp` is a high-performance native C/C++ inference runtime.
- Android UI/orchestration is Kotlin, so JNI bridges Kotlin <-> native C++.

JNI responsibilities in this project:

- Load model: `nativeLoadModel(...)`
- Start session/context: `nativeStartSession(...)`
- Start generation request: `nativeGenerateStart(...)`
- Poll next token: `nativePollToken(...)`
- Stop generation: `nativeStop(...)`
- Unload model: `nativeUnloadModel(...)`

Native layer also handles:

- Token streaming queue
- Stop/cancel signal
- UTF-8 stream safety (partial multi-byte token handling)

Core file:

- `app/src/main/cpp/gguf_jni.cpp`

---

## How `llama.cpp` generates responses

Inside native generation flow:

1. Load GGUF model with `llama_model_load_from_file`.
2. Create context with `llama_init_from_model`.
3. Build chat prompt template (`llama_model_chat_template` + `llama_chat_apply_template`).
4. Tokenize prompt (`llama_tokenize`).
5. Decode/evaluate context (`llama_decode` / encoder path if required).
6. Sample next token using sampler chain:
   - top-k
   - top-p
   - temperature / greedy
7. Convert token -> text piece and stream to Kotlin side.
8. Stop on EOG/max tokens/stop signal.

This gives token-by-token streaming behavior in chat UI.

---

## Quantization techniques used

This app is designed for GGUF quantized models to run on mobile CPU/RAM budgets.

Important implementation detail:

- Quantization is **not** sent as a separate runtime parameter to `llama.cpp`.
- The selected quant is represented by the selected GGUF file itself (example: `...Q4_K_M.gguf`, `...Q8_0.gguf`).
- `llama.cpp` reads quantization from GGUF metadata during model load.

Current quantization handling:

- Default quant preference: `Q4_K_M`
- Supported file tags parsed from filenames (examples):
  - `Q2_K`
  - `Q3_K`
  - `Q4_K_M`, `Q4_K_S`
  - `Q5_K_M`, `Q5_K_S`
  - `Q6_K`
  - `Q8_0`

Why quantization matters:

- Lower bit quant (Q2/Q3/Q4) -> smaller memory + faster, lower quality.
- Higher quant (Q5/Q6/Q8) -> better quality, slower and larger memory footprint.

The app exposes quant choices per model and uses device heuristics (RAM/storage checks) before download/run.

---

## Runtime parameters sent to `llama.cpp`

At runtime, this app sends **inference/session configuration**, not a standalone quant flag:

- On model load:
  - `modelPath`
  - `contextLength`
  - `threads`
  - `gpuLayers`
- On generation:
  - `prompt`
  - `temperature`
  - `topP`
  - `maxTokens`

Code references:

- Kotlin JNI bridge:
  - `app/src/main/java/com/android/gguf_llama_jin/inference/LlmNativeBridgeImpl.kt`
- Native JNI implementation:
  - `app/src/main/cpp/gguf_jni.cpp`

---

## Optional web search grounding (tool layer)

Web search is orchestrated in Kotlin (not native model browsing):

- Gate decides: `SKIP`, `SEARCH`, or `SUGGEST`
- Provider fetches top results
- App builds grounded prompt with citations `[1..3]`
- Local model answers using provided context

Current provider path includes Tavily + mock fallback when API key is missing.

---

## Build notes

Basic build:

```bash
./gradlew assembleDebug
```

Run with Tavily API key:

```bash
./gradlew assembleDebug -PtavilyApiKey=YOUR_KEY
```
