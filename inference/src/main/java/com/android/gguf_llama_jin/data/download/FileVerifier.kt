package com.android.gguf_llama_jin.data.download

import java.io.File
import java.security.MessageDigest

object FileVerifier {
    fun verifySize(file: File, expectedSize: Long?): Boolean {
        return expectedSize == null || file.length() == expectedSize
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }

        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun verifySha(file: File, expectedSha: String?): Boolean {
        if (expectedSha.isNullOrBlank()) return true
        return sha256(file).equals(expectedSha, ignoreCase = true)
    }
}
