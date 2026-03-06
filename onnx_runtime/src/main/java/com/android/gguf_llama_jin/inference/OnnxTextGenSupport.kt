package com.android.gguf_llama_jin.inference

import ai.onnxruntime.OrtSession
import java.nio.charset.StandardCharsets
import kotlin.math.exp
import kotlin.random.Random
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal data class OnnxGraphSignature(
    val inputIdsName: String,
    val attentionMaskName: String?,
    val positionIdsName: String?,
    val tokenTypeIdsName: String?,
    val logitsName: String,
    val pastInputNames: List<String>,
    val presentOutputNames: List<String>
)

internal object OnnxGraphInspector {
    private val inputIdCandidates = listOf("input_ids", "inputids")
    private val attentionMaskCandidates = listOf("attention_mask", "attentionmask")
    private val positionIdsCandidates = listOf("position_ids", "positionids")
    private val tokenTypeCandidates = listOf("token_type_ids", "tokentypeids")

    fun inspect(session: OrtSession): Result<OnnxGraphSignature> {
        return runCatching {
            val inputNames = session.inputInfo.keys.toList()
            val outputNames = session.outputInfo.keys.toList()
            if (inputNames.isEmpty() || outputNames.isEmpty()) {
                error("ONNX graph has no inputs or outputs")
            }

            val inputIds = findCandidate(inputNames, inputIdCandidates)
                ?: error("Unsupported ONNX graph: missing input_ids tensor")
            val attentionMask = findCandidate(inputNames, attentionMaskCandidates)
            val positionIds = findCandidate(inputNames, positionIdsCandidates)
            val tokenTypeIds = findCandidate(inputNames, tokenTypeCandidates)
            val logits = outputNames.firstOrNull { it.contains("logits", ignoreCase = true) }
                ?: outputNames.firstOrNull()
                ?: error("Unsupported ONNX graph: missing logits output")

            val pastInputs = inputNames.filter { isCacheName(it) }
            val presentOutputs = outputNames.filter { isCacheName(it) || it.contains("present", ignoreCase = true) }

            val knownNames = mutableSetOf(inputIds)
            attentionMask?.let { knownNames += it }
            positionIds?.let { knownNames += it }
            tokenTypeIds?.let { knownNames += it }
            knownNames += pastInputs
            val unknown = inputNames.filter { it !in knownNames }
            if (unknown.isNotEmpty()) {
                error(
                    "Unsupported ONNX graph inputs: ${unknown.joinToString()}. " +
                        "Expected decoder-style inputs like input_ids/attention_mask/position_ids."
                )
            }

            OnnxGraphSignature(
                inputIdsName = inputIds,
                attentionMaskName = attentionMask,
                positionIdsName = positionIds,
                tokenTypeIdsName = tokenTypeIds,
                logitsName = logits,
                pastInputNames = pastInputs,
                presentOutputNames = presentOutputs
            )
        }
    }

    private fun findCandidate(names: List<String>, candidates: List<String>): String? {
        val normalized = names.associateBy { it.replace("_", "").lowercase() }
        return candidates.firstNotNullOfOrNull { normalized[it] }
    }

    private fun isCacheName(name: String): Boolean {
        return name.contains("past", ignoreCase = true) ||
            name.contains("cache", ignoreCase = true) ||
            name.contains("key_values", ignoreCase = true)
    }
}

internal class HfTokenizer private constructor(
    private val tokenToId: Map<String, Long>,
    private val idToToken: Map<Long, String>,
    private val mergeRanks: Map<Pair<String, String>, Int>,
    private val specialTokenIds: Set<Long>,
    val eosTokenIds: Set<Long>,
    private val unkId: Long?
) {
    private val bpeCache = mutableMapOf<String, List<String>>()
    private val textRegex = Regex("'s|'t|'re|'ve|'m|'ll|'d| ?\\p{L}+| ?\\p{N}+| ?[^\\s\\p{L}\\p{N}]+|\\s+(?!\\S)|\\s+")
    private val byteToUnicode = buildByteToUnicode()
    private val unicodeToByte = byteToUnicode.entries.associate { it.value to it.key }

    fun encode(text: String): LongArray {
        val ids = mutableListOf<Long>()
        textRegex.findAll(text).forEach { match ->
            val token = bytesToUnicodeString(match.value)
            bpe(token).forEach { piece ->
                val id = tokenToId[piece] ?: unkId
                if (id != null) ids += id
            }
        }
        return ids.toLongArray()
    }

    fun decode(ids: List<Long>): String {
        val bytes = ArrayList<Byte>()
        ids.forEach { id ->
            if (id in specialTokenIds) return@forEach
            val token = idToToken[id] ?: return@forEach
            token.forEach { ch ->
                val b = unicodeToByte[ch]
                if (b != null) {
                    bytes += b.toByte()
                } else {
                    ch.toString().toByteArray(StandardCharsets.UTF_8).forEach(bytes::add)
                }
            }
        }
        return bytes.toByteArray().toString(StandardCharsets.UTF_8)
    }

    private fun bytesToUnicodeString(input: String): String {
        val sb = StringBuilder()
        input.toByteArray(StandardCharsets.UTF_8).forEach { byte ->
            val unsigned = byte.toInt() and 0xFF
            sb.append(byteToUnicode[unsigned])
        }
        return sb.toString()
    }

    private fun bpe(token: String): List<String> {
        bpeCache[token]?.let { return it }
        if (token.length <= 1) return listOf(token)
        var word = token.map { it.toString() }.toMutableList()
        while (true) {
            val pairs = mutableListOf<Pair<String, String>>()
            for (i in 0 until word.lastIndex) {
                pairs += word[i] to word[i + 1]
            }
            val best = pairs.minByOrNull { mergeRanks[it] ?: Int.MAX_VALUE } ?: break
            if (mergeRanks[best] == null) break

            val merged = mutableListOf<String>()
            var i = 0
            while (i < word.size) {
                if (i < word.lastIndex && word[i] == best.first && word[i + 1] == best.second) {
                    merged += word[i] + word[i + 1]
                    i += 2
                } else {
                    merged += word[i]
                    i++
                }
            }
            word = merged
            if (word.size == 1) break
        }
        return word.also { bpeCache[token] = it }
    }

    companion object {
        fun fromJson(rawJson: String): Result<HfTokenizer> = runCatching {
            val root = Json.parseToJsonElement(rawJson).jsonObject
            val model = root["model"]?.jsonObject ?: error("tokenizer.json missing model")
            val modelType = model["type"]?.jsonPrimitive?.contentOrNull
            if (!modelType.equals("BPE", ignoreCase = true)) {
                error("Unsupported tokenizer model type: ${modelType ?: "unknown"} (only BPE is supported)")
            }

            val vocabObj = model["vocab"]?.jsonObject ?: error("tokenizer.json missing model.vocab")
            val tokenToId = mutableMapOf<String, Long>()
            vocabObj.forEach { (token, idElement) ->
                val id = idElement.jsonPrimitive.longOrNull ?: return@forEach
                tokenToId[token] = id
            }
            if (tokenToId.isEmpty()) error("tokenizer.json has empty vocab")

            val merges = model["merges"]?.jsonArray ?: JsonArray(emptyList())
            val mergeRanks = mutableMapOf<Pair<String, String>, Int>()
            merges.forEachIndexed { index, element ->
                val pair = parseMergePair(element) ?: return@forEachIndexed
                mergeRanks[pair] = index
            }

            val addedTokenMap = mutableMapOf<String, Long>()
            val specialTokenIds = mutableSetOf<Long>()
            val eosTokenIds = mutableSetOf<Long>()
            root["added_tokens"]?.jsonArray?.forEach { el ->
                val obj = el.jsonObject
                val content = obj["content"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val id = obj["id"]?.jsonPrimitive?.longOrNull ?: return@forEach
                addedTokenMap[content] = id
                val isSpecial = obj["special"]?.jsonPrimitive?.booleanOrNull == true
                if (isSpecial) specialTokenIds += id
                if (looksLikeEos(content)) eosTokenIds += id
            }

            val unkToken = model["unk_token"]?.jsonPrimitive?.contentOrNull
            val mergedTokenToId = tokenToId + addedTokenMap
            val idToToken = mergedTokenToId.entries.associate { (token, id) -> id to token }
            val unkId = unkToken?.let { mergedTokenToId[it] }

            HfTokenizer(
                tokenToId = mergedTokenToId,
                idToToken = idToToken,
                mergeRanks = mergeRanks,
                specialTokenIds = specialTokenIds,
                eosTokenIds = eosTokenIds,
                unkId = unkId
            )
        }

        private fun parseMergePair(element: JsonElement): Pair<String, String>? {
            return when (element) {
                is JsonArray -> {
                    val first = element.getOrNull(0)?.jsonPrimitive?.contentOrNull
                    val second = element.getOrNull(1)?.jsonPrimitive?.contentOrNull
                    if (first != null && second != null) first to second else null
                }
                is JsonObject -> null
                else -> {
                    val content = element.jsonPrimitive.contentOrNull ?: return null
                    val parts = content.trim().split(Regex("\\s+"))
                    if (parts.size >= 2) parts[0] to parts[1] else null
                }
            }
        }

        private fun looksLikeEos(token: String): Boolean {
            val t = token.lowercase()
            return t.contains("eos") || t.contains("endoftext") || t == "</s>" || t.contains("eot")
        }

        private fun buildByteToUnicode(): Map<Int, Char> {
            val bs = mutableListOf<Int>()
            bs += (33..126)
            bs += (161..172)
            bs += (174..255)

            val cs = bs.toMutableList()
            var n = 0
            for (b in 0..255) {
                if (b !in bs) {
                    bs += b
                    cs += 256 + n
                    n++
                }
            }
            return bs.zip(cs).associate { (b, c) -> b to c.toChar() }
        }
    }
}

internal object OnnxSampler {
    fun sampleNextToken(
        logits: FloatArray,
        temperature: Float,
        topP: Float,
        random: Random = Random.Default
    ): Int {
        if (logits.isEmpty()) error("Cannot sample from empty logits")
        if (temperature <= 1e-6f) {
            var bestIdx = 0
            var bestValue = logits[0]
            for (i in 1 until logits.size) {
                if (logits[i] > bestValue) {
                    bestValue = logits[i]
                    bestIdx = i
                }
            }
            return bestIdx
        }

        val scaled = DoubleArray(logits.size)
        var maxLogit = Double.NEGATIVE_INFINITY
        for (i in logits.indices) {
            val value = logits[i] / temperature
            scaled[i] = value.toDouble()
            if (value > maxLogit) maxLogit = value.toDouble()
        }
        val expVals = DoubleArray(logits.size)
        var sum = 0.0
        for (i in scaled.indices) {
            val e = exp(scaled[i] - maxLogit)
            expVals[i] = e
            sum += e
        }
        if (sum <= 0.0) return scaled.indices.maxByOrNull { scaled[it] } ?: 0

        val probs = expVals.mapIndexed { idx, value -> idx to (value / sum) }.sortedByDescending { it.second }
        val threshold = topP.coerceIn(0.05f, 1f).toDouble()
        val filtered = mutableListOf<Pair<Int, Double>>()
        var cumulative = 0.0
        for ((id, p) in probs) {
            filtered += id to p
            cumulative += p
            if (cumulative >= threshold && filtered.size > 1) break
        }
        val filteredSum = filtered.sumOf { it.second }.takeIf { it > 0.0 } ?: 1.0
        val target = random.nextDouble()
        var acc = 0.0
        filtered.forEach { (id, p) ->
            acc += p / filteredSum
            if (target <= acc) return id
        }
        return filtered.last().first
    }
}
