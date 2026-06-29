package com.example

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class LlamaResponse(
    val response_text: String,
    val actions: List<LlamaAction>
)

@JsonClass(generateAdapter = true)
data class LlamaAction(
    val type: String,
    val title: String? = null,
    val start_time_offset_mins: Int? = null,
    val duration_mins: Int? = null,
    val hour: Int? = null,
    val minute: Int? = null,
    val message: String? = null
)
