package com.llmhub.llmhub.data

import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipFile

/**
 * Lightweight integrity checks for downloaded model files.
 * Provides format-specific validation (magic bytes) and relaxed size checks.
 */
private fun log(tag: String, msg: String) {
    try {
        android.util.Log.d(tag, msg)
    } catch (_: Exception) {
        println("[$tag] $msg")
    }
}

/**
 * Lightweight integrity checks for downloaded model files.
 * Provides format-specific validation (magic bytes) and relaxed size checks.
 */
fun isModelFileValid(file: File, modelFormat: String, expectedSizeBytes: Long = 0L): Boolean {
    val tag = "ModelIntegrity"
    if (!file.exists()) {
        log(tag, "File does not exist: ${file.absolutePath}")
        return false
    }
    
    // For directories (multi-file models), sum the size of all files
    val actualSize = if (file.isDirectory) {
        file.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
    } else {
        file.length()
    }
    log(tag, "Validating: ${file.name}, format: $modelFormat, isDir: ${file.isDirectory}, actual: $actualSize, expected: $expectedSizeBytes")

    // Absolute minimum: 1MB (except for very small directories which shouldn't happen for LLMs)
    if (actualSize < 1L * 1024 * 1024) {
        log(tag, "FAILURE: File too small for any LLM model (<1MB)")
        return false
    }

    return when (modelFormat.lowercase()) {
        "gguf", "bin" -> {
            val magicOk = isGgufValid(file)
            // For GGUF, if magic is OK, allow size down to 50% of metadata (quantization variances)
            // or 90% if magic is NOT found (unlikely for GGUF, but maybe it's a raw bin)
            val threshold = if (magicOk) 0.50 else 0.90
            val sizeOk = if (expectedSizeBytes > 0) actualSize >= (expectedSizeBytes * threshold).toLong() else true

            if (!magicOk) log(tag, "GGUF magic mismatch for ${file.name}")
            if (!sizeOk) log(tag, "GGUF size failure: $actualSize < ${expectedSizeBytes * threshold} (target: $expectedSizeBytes)")

            magicOk && sizeOk
        }
        "task", "litertlm" -> {
            val zipOk = isTaskLikelyValid(file)
            val sizeOk = if (expectedSizeBytes > 0) actualSize >= (expectedSizeBytes * 0.90).toLong() else true

            if (!zipOk) log(tag, "Task/ZIP magic mismatch for ${file.name}")
            if (!sizeOk) log(tag, "Task size failure: $actualSize < ${expectedSizeBytes * 0.90} (target: $expectedSizeBytes)")

            zipOk && sizeOk
        }
        "onnx" -> {
            val sizeOk = if (expectedSizeBytes > 0) actualSize >= (expectedSizeBytes * 0.90).toLong() else true
            if (!sizeOk) log(tag, "ONNX size failure: $actualSize < ${expectedSizeBytes * 0.90}")
            sizeOk
        }
        else -> {
            val sizeOk = if (expectedSizeBytes > 0) actualSize >= (expectedSizeBytes * 0.90).toLong() else true
            sizeOk
        }
    }
}

private fun isTaskLikelyValid(file: File): Boolean {
    // 1) Try checking ZIP magic ("PK") first for performance
    try {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() >= 4) {
                val sig = ByteArray(4)
                raf.readFully(sig)
                if (sig[0] == 'P'.code.toByte() && sig[1] == 'K'.code.toByte() && sig[2] == 0x03.toByte() && sig[3] == 0x04.toByte()) {
                    return true
                }
            }
        }
    } catch (_: Exception) { }

    // 2) Try opening as ZIP
    return try {
        ZipFile(file).use { true }
    } catch (_: Exception) {
        // 3) Fallback: if >= 10MB, assume it might be a valid FlatBuffer/raw format
        file.length() >= 10L * 1024 * 1024
    }
}

private fun isGgufValid(file: File): Boolean {
    return try {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 4) return false
            val magic = ByteArray(4)
            raf.readFully(magic)
            // GGUF magic: 'G' 'G' 'U' 'F'
            magic[0] == 'G'.code.toByte() && magic[1] == 'G'.code.toByte() && magic[2] == 'U'.code.toByte() && magic[3] == 'F'.code.toByte()
        }
    } catch (_: Exception) {
        false
    }
}
