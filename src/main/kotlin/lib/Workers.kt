package com.rinha.lib

import com.rinha.providers.RedisClient
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory

object Workers {

    private val logger = LoggerFactory.getLogger(Workers::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val WORKERS = System.getenv("WORKERS")?.toIntOrNull() ?: 2

    fun start() {
        repeat(WORKERS) { id ->
            scope.launch {
                logger.info("worker-$id started")
                while (isActive) {
                    try {
                        processQueue()
                        delay(100)
                    } catch (e: Exception) {
                        logger.error("worker-$id failed: ${e.message}", e)
                        delay(100)
                    }
                }
            }
        }
    }

    fun stop() {
        scope.cancel()
    }

    private suspend fun processQueue() {
        RedisClient.launchAsyncJedis { jedis ->
            val uuid = jedis.rpop("payments:queue") ?: run {
                delay(50)
                return@run
            }

            val key = "payments:$uuid"
            val fields = jedis.hgetAll(key)

            if (fields.isEmpty()) {
                logger.warn("no payment in queue")
                return@launchAsyncJedis
            }

            logger.info("processing payment: $uuid")

            val order = PaymentRequest(
                correlationId = fields["correlation_id"]!!,
                amount = fields["amount"]!!.toBigDecimal(),
                requestedAt = fields["requested_at"]!!
            )

            try {
                Activities.pay(order)
                jedis.del(key)
            } catch (e: Exception) {
                logger.error("failed to process payment $uuid: ${e.message}")
            }

        }
    }

}