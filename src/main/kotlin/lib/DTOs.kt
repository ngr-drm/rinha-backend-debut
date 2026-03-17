package com.rinha.lib

import kotlinx.serialization.*
import kotlinx.coroutines.channels.Channel
import java.math.BigDecimal

@Serializable
data class PaymentRequest(
    val correlationId: String,
    @Contextual val amount: BigDecimal,
    val requestedAt: String? = null,
)

data class QueuedPayment(val request: PaymentRequest, val retries: Int = 0)
const val MAX_RETRIES = 5

@Serializable
data class Summary(
    val totalRequests: Int,
    @Contextual
    val totalAmount: BigDecimal,
)

@Serializable
data class Audit(
    val default: Summary,
    val fallback: Summary
)

@Serializable
data class ProcessorHealthResponse(
    val failing: Boolean,
    val minResponseTime: Long
)

class Queue<T>(maxSize: Int = 50_000) {
    private val channel = Channel<T>(maxSize)

    suspend fun put(item: T) = channel.send(item)

    fun trySend(item: T): Boolean = channel.trySend(item).isSuccess

    suspend fun get(): T = channel.receive()
}
