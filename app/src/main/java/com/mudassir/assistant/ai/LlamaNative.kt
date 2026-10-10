package com.mudassir.assistant.ai

/** Direct bindings to llama_jni.cpp. Use LocalBrain instead of calling these directly. */
object LlamaNative {
    init {
        System.loadLibrary("llama_jni")
    }

    /** Receives generated text as UTF-8 bytes; return false to stop generating. */
    fun interface TextCallback {
        fun onText(bytes: ByteArray): Boolean
    }

    const val ERR_TEMPLATE = -1
    const val ERR_TOO_LONG = -2
    const val ERR_DECODE = -3
    const val ERR_TOKENIZE = -4

    @JvmStatic external fun nativeLoad(path: String, nCtx: Int, nThreads: Int): Long
    @JvmStatic external fun nativeFree(handle: Long)
    @JvmStatic external fun nativeStop(handle: Long)
    @JvmStatic external fun nativeReset(handle: Long)
    @JvmStatic external fun nativeGenerate(
        handle: Long, roles: Array<String>, contents: Array<Any>,
        maxTokens: Int, temperature: Float, callback: TextCallback
    ): Int
}
