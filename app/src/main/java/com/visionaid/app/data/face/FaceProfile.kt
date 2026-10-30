package com.visionaid.app.data.face

import java.util.UUID

data class FaceProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val embedding: List<Float>,
    val photoBase64: String? = null,
    val enrolledAt: Long = System.currentTimeMillis()
)
