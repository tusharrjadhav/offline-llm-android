package com.android.gguf_llama_jin.inference

import com.android.gguf_llama_jin.core.AppLogger
import com.android.gguf_llama_jin.core.ModelRuntime
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OnnxTensor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.nio.LongBuffer
import java.util.concurrent.atomic.AtomicBoolean

class OnnxRuntimeBridgeImpl : LlmRuntimeBridge {
    override val runtime: ModelRuntime = ModelRuntime.ONNX

    private var env: OrtEnvironment? = null
    private val sessions = mutableMapOf<Long, OnnxSessionState>()
    private var handleCounter = 10_000L

    override fun loadModel(modelRef: RuntimeModelRef, contextLength: Int, threads: Int, gpuLayers: Int): Long {
        return try {
            val file = File(modelRef.path)
            if (!file.exists()) {
                AppLogger.e("ONNX model file not found: ${modelRef.path}")
                return 0L
            }
            val bundleDir = modelRef.assetDir?.let(::File) ?: file.parentFile
            if (bundleDir == null || !bundleDir.exists()) {
                AppLogger.e("ONNX asset directory not found for model: ${modelRef.path}")
                return 0L
            }

            val tokenizerFile = File(bundleDir, "tokenizer.json")
            val tokenizerResult = if (tokenizerFile.exists()) {
                HfTokenizer.fromJson(tokenizerFile.readText())
            } else {
                Result.failure(IllegalStateException("Missing tokenizer.json in ONNX model bundle"))
            }

            if (env == null) env = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(threads)
            }
            val session = env!!.createSession(modelRef.path, options)
            val signatureResult = OnnxGraphInspector.inspect(session)
            val handle = handleCounter++
            val unsupportedReason = firstError(
                tokenizerResult.exceptionOrNull()?.message,
                signatureResult.exceptionOrNull()?.message
            )
            val state = OnnxSessionState(
                session = session,
                tokenizer = tokenizerResult.getOrNull(),
                signature = signatureResult.getOrNull(),
                contextLength = contextLength,
                unsupportedReason = unsupportedReason
            )
            sessions[handle] = state
            AppLogger.i(
                "ONNX session created. handle=$handle path=${modelRef.path} " +
                    "compatible=${unsupportedReason == null}"
            )
            handle
        } catch (t: Throwable) {
            AppLogger.e("ONNX load failed", t)
            0L
        }
    }

    override fun startSession(handle: Long, systemPrompt: String?): Long {
        val state = sessions[handle] ?: return 0L
        state.systemPrompt = systemPrompt
        return handle
    }

    override fun generate(sessionHandle: Long, prompt: String, params: SamplingParams): Flow<TokenChunk> = flow<TokenChunk> {
        val state = sessions[sessionHandle]
        if (state == null) {
            throw InferenceError.SessionFailed("ONNX session is not available")
        }
        state.cancelRequested.set(false)
        state.unsupportedReason?.let { throw InferenceError.SessionFailed(it) }
        val session = state.session ?: throw InferenceError.SessionFailed("ONNX session is not initialized")
        val signature = state.signature ?: throw InferenceError.SessionFailed("ONNX graph signature is unavailable")
        val tokenizer = state.tokenizer ?: throw InferenceError.SessionFailed("ONNX tokenizer is unavailable")

        val promptWithContext = buildPrompt(state.systemPrompt, prompt)
        AppLogger.i(
            "ONNX generate start session=$sessionHandle promptLen=${prompt.length} " +
                "promptWithContextLen=${promptWithContext.length}"
        )
        val inputTokens = tokenizer.encode(promptWithContext).toMutableList()
        if (inputTokens.isEmpty()) {
            throw InferenceError.SessionFailed("Prompt tokenization produced no tokens")
        }
        val generatedTokenIds = mutableListOf<Long>()
        var emittedText = ""
        var generated = 0

        while (!state.cancelRequested.get() && generated < params.maxTokens) {
            val fullSequence = (inputTokens + generatedTokenIds).takeLast(state.contextLength)
            if (fullSequence.isEmpty()) break

            val logits = runStep(
                session = session,
                signature = signature,
                tokenIds = fullSequence
            )
            val nextToken = chooseNextToken(
                logits = logits,
                temperature = params.temperature,
                topP = params.topP,
                eosTokenIds = tokenizer.eosTokenIds,
                allowEos = generatedTokenIds.isNotEmpty()
            ).toLong()

            if (nextToken in tokenizer.eosTokenIds) {
                AppLogger.i("ONNX generate finished by eos token session=$sessionHandle generated=$generated")
                break
            }

            generatedTokenIds += nextToken
            generated++
            val decoded = tokenizer.decode(generatedTokenIds)
            if (decoded.length > emittedText.length) {
                val delta = decoded.substring(emittedText.length)
                if (delta.isNotEmpty()) {
                    emit(TokenChunk(token = delta))
                }
                emittedText = decoded
            }
        }

        if (generatedTokenIds.isEmpty()) {
            throw InferenceError.SessionFailed(
                "ONNX generated no output tokens. Model may be incompatible with this runtime path."
            )
        }
        AppLogger.i("ONNX generate done session=$sessionHandle generated=$generated emittedChars=${emittedText.length}")
        emit(TokenChunk(token = "", isDone = true))
    }.flowOn(Dispatchers.IO)

    override fun stop(sessionHandle: Long) {
        sessions[sessionHandle]?.cancelRequested?.set(true)
    }

    override fun unloadModel(handle: Long) {
        sessions.remove(handle)?.session?.close()
    }

    private fun runStep(
        session: OrtSession,
        signature: OnnxGraphSignature,
        tokenIds: List<Long>
    ): FloatArray {
        val env = env ?: throw InferenceError.SessionFailed("ONNX environment unavailable")
        val inputMap = LinkedHashMap<String, OnnxTensor>()
        val seqLen = tokenIds.size
        return try {
            inputMap[signature.inputIdsName] = createInt64Tensor(env, tokenIds.toLongArray(), longArrayOf(1, seqLen.toLong()))
            signature.attentionMaskName?.let { name ->
                val mask = LongArray(seqLen) { 1L }
                inputMap[name] = createInt64Tensor(env, mask, longArrayOf(1, seqLen.toLong()))
            }
            signature.positionIdsName?.let { name ->
                val positions = LongArray(seqLen) { it.toLong() }
                inputMap[name] = createInt64Tensor(env, positions, longArrayOf(1, seqLen.toLong()))
            }
            signature.tokenTypeIdsName?.let { name ->
                val typeIds = LongArray(seqLen) { 0L }
                inputMap[name] = createInt64Tensor(env, typeIds, longArrayOf(1, seqLen.toLong()))
            }

            session.run(inputMap).use { result ->
                val maybeNamed = result.get(signature.logitsName)
                val value = when {
                    maybeNamed.isPresent -> maybeNamed.get()
                    result.size() > 0 -> result.get(0)
                    else -> null
                } ?: throw InferenceError.SessionFailed("ONNX output logits not found")

                if (value !is OnnxTensor) {
                    throw InferenceError.SessionFailed("ONNX logits output is not a tensor")
                }
                extractLastTokenLogits(value)
            }
        } catch (e: OrtException) {
            throw InferenceError.SessionFailed("ONNX inference failed: ${e.message ?: "unknown OrtException"}")
        } finally {
            inputMap.values.forEach { runCatching { it.close() } }
        }
    }

    private fun createInt64Tensor(env: OrtEnvironment, values: LongArray, shape: LongArray): OnnxTensor {
        return OnnxTensor.createTensor(env, LongBuffer.wrap(values), shape)
    }

    private fun extractLastTokenLogits(logitsTensor: OnnxTensor): FloatArray {
        val info = logitsTensor.info
        val shape = info.shape
        val raw = logitsTensor.floatBuffer
        val data = FloatArray(raw.remaining())
        raw.get(data)

        if (shape.isEmpty()) {
            throw InferenceError.SessionFailed("ONNX logits shape is empty")
        }
        val vocabSize = shape.last().toInt()
        if (vocabSize <= 0 || data.size < vocabSize) {
            throw InferenceError.SessionFailed("ONNX logits shape is invalid")
        }
        val offset = data.size - vocabSize
        return data.copyOfRange(offset, data.size)
    }

    private fun firstError(vararg messages: String?): String? {
        return messages.firstOrNull { !it.isNullOrBlank() }
    }

    private fun chooseNextToken(
        logits: FloatArray,
        temperature: Float,
        topP: Float,
        eosTokenIds: Set<Long>,
        allowEos: Boolean
    ): Int {
        val sampled = OnnxSampler.sampleNextToken(
            logits = logits,
            temperature = temperature,
            topP = topP
        )
        if (allowEos || sampled.toLong() !in eosTokenIds) return sampled

        var bestIndex = -1
        var bestValue = Float.NEGATIVE_INFINITY
        logits.forEachIndexed { index, value ->
            if (index.toLong() in eosTokenIds) return@forEachIndexed
            if (value > bestValue) {
                bestValue = value
                bestIndex = index
            }
        }
        return if (bestIndex >= 0) bestIndex else sampled
    }

    private fun buildPrompt(systemPrompt: String?, userPrompt: String): String {
        val sys = systemPrompt?.trim().orEmpty()
        val user = userPrompt.trim()
        return if (sys.isBlank()) user else "$sys\n\n$user"
    }

    private data class OnnxSessionState(
        val session: OrtSession?,
        val tokenizer: HfTokenizer?,
        val signature: OnnxGraphSignature?,
        val contextLength: Int,
        val unsupportedReason: String?,
        val cancelRequested: AtomicBoolean = AtomicBoolean(false),
        var systemPrompt: String? = null
    )
}
