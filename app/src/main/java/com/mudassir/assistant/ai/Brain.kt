package com.mudassir.assistant.ai

/** One message of the conversation sent to a brain. */
data class Turn(val role: String, val text: String) {
    companion object {
        const val SYSTEM = "system"
        const val USER = "user"
        const val ASSISTANT = "assistant"
    }
}

class BrainException(message: String) : Exception(message)
