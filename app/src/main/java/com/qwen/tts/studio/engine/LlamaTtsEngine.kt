package com.qwen.tts.studio.engine

class LlamaTtsEngine : AutoCloseable {
    data class Result(val audio: FloatArray, val sampleRate: Int, val frames: Int)

    private var handle = 0L
    private var closed = false

    @Synchronized
    fun load(modelPath: String, mmprojPath: String, nativeLibraryDir: String, vulkan: Boolean, threads: Int) {
        check(!closed) { "The speech engine is closed." }
        check(handle == 0L) { "Unload the current model first." }
        nativeLibrary.value
        handle = nativeLoad(modelPath, mmprojPath, nativeLibraryDir, vulkan, threads)
        check(handle != 0L) { "Could not load the speech model." }
    }

    @Synchronized
    fun synthesize(text: String, referenceWav: String?, language: String, maxFrames: Int): Result {
        check(handle != 0L) { "Load the speech model first." }
        return nativeSynthesize(handle, text.toByteArray(Charsets.UTF_8), referenceWav, language, maxFrames)
    }

    @Synchronized
    fun unload() {
        if (handle != 0L) {
            nativeClose(handle)
            handle = 0L
        }
    }

    @Synchronized
    override fun close() {
        closed = true
        unload()
    }

    private external fun nativeLoad(
        modelPath: String,
        mmprojPath: String,
        nativeLibraryDir: String,
        vulkan: Boolean,
        threads: Int,
    ): Long

    private external fun nativeSynthesize(
        handle: Long,
        textUtf8: ByteArray,
        referenceWav: String?,
        language: String,
        maxFrames: Int,
    ): Result

    private external fun nativeClose(handle: Long)

    private companion object {
        val nativeLibrary = lazy { System.loadLibrary("qwen_llama_tts_jni") }
    }
}
