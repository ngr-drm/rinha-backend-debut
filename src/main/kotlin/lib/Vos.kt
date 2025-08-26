package com.rinha.lib

import kotlinx.serialization.*
import java.math.BigDecimal

@Serializable
data class PaymentRequest(
    val correlationId: String,
    @Contextual
    val amount: BigDecimal,
    val requestedAt: String? = null,
)

@Serializable
data class Summary(
    val totalRequests: Int = 0,
    @Contextual
    val amount: BigDecimal = 0.toBigDecimal(),
)

@Serializable
data class Audit(
    val default: Summary,
    val fallback: Summary
)

data class Payment(
    val order: PaymentRequest,
    var processed: Boolean = false,
    var processor: String? = null,
)

@Serializable
data class ProcessorHealthResponse(
    val failing: Boolean,
    val minResponseTime: Long
)