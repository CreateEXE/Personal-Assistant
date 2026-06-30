package com.example

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class LlamaResponse(
    val thought_process: String? = null,
    val response_text: String? = null
)
