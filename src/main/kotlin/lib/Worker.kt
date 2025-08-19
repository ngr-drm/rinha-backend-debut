package com.rinha.lib

import com.rinha.lib.Activities.queue
import com.rinha.providers.RedisClient
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory

object Worker {

    private val logger = LoggerFactory.getLogger(Worker::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start() {
        scope.launch {
            while (isActive) {
                processQueue()
                delay(60_000)
            }
        }
    }

    fun stop() {
        scope.cancel()
    }

    private suspend fun processQueue() {

        try {
            RedisClient.withJedis { jedis ->
                while (true) {
                    val uuid = jedis.rpop("payments:queue") ?: break
                    val key = "payments:$uuid"
                    val fields = jedis.hgetAll(key)

                    if (fields.isEmpty()) {
                        logger.warn("payment $uuid not found in Redis")
                        throw Exception("failed to process payment")
                    }

                    logger.info("processing payment: $uuid")

                    val order = PaymentRequest(
                        correlationId = fields["correlation_id"]!!,
                        amount = fields["amount"]!!.toBigDecimal(),
                        requestedAt = fields["requested_at"]!!
                    )

                    val payment =  launchPaymentProcessor(order)

                    if(!payment.processed) {
                        queue(payment.order)
                    }

                }
            }
        } catch (e: Exception) {
            logger.error("error processing queue: ${e.message}", e)
        }
    }

    private suspend fun launchPaymentProcessor(order: PaymentRequest): Payment {
        val payment =  Payment(order = order)

            try {
                val isAccept = Activities.makePayment(order)

                if (isAccept) {
                    logger.info("payment processed successfully: ${order.correlationId}")
                    payment.processed = true
                }
            } catch (e: Exception) {
                logger.error("failed to process payment ${order.correlationId}: ${e.message}", e)
                throw Exception("failed to process payment")
            }

        return payment
    }

}