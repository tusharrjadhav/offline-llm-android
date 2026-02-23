package com.android.gguf_llama_jin

import com.android.gguf_llama_jin.data.download.FileVerifier
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FileVerifierTest {
    @Test
    fun verifySize_checks_expected_size() {
        val temp = File.createTempFile("verify", ".bin")
        temp.writeBytes(byteArrayOf(1, 2, 3, 4))

        assertTrue(FileVerifier.verifySize(temp, 4))
        assertFalse(FileVerifier.verifySize(temp, 5))

        temp.delete()
    }
}
