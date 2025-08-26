package com.rinha.lib

import com.rinha.providers.Http.client
import com.rinha.providers.RedisClient
import com.rinha.providers.SQLiteManager
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import org.slf4j.LoggerFactory

object Activities {

    private val logger = LoggerFactory.getLogger(Activities::class.java)

    suspend fun queue(order: PaymentRequest){

        RedisClient.launchAsyncJedis { jedis ->

            val processed = false

            jedis.lpush("payments:queue", order.correlationId)
            jedis.hset("payments:${order.correlationId}", mapOf(
                "correlation_id" to order.correlationId,
                "amount" to order.amount.toString(),
                "requested_at" to order.requestedAt,
                "status" to processed.toString()
            ))
        }
    }

    fun toAudit(from: String, to: String): Audit {

        lateinit var result: Audit

        SQLiteManager.getReadConnection("data/payments.db").use { conn ->
            val sql = """
            SELECT 
                processor,
                COUNT(*) as totalRequests,
                SUM(CAST(amount AS REAL)) as totalAmount
            FROM payments
            WHERE 
                requested_at BETWEEN ? AND ?
                AND processed = true
            GROUP BY processor
        """.trimIndent()

            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, from)
                ps.setString(2, to)
                val rs = ps.executeQuery()

                var default = Summary()
                var fallback = Summary()

                while (rs.next()) {
                    when (rs.getString("processor")) {
                        "default" -> default = Summary(
                            rs.getInt("totalRequests"),
                            rs.getBigDecimal("totalAmount")
                        )
                        "fallback" -> fallback = Summary(
                            rs.getInt("totalRequests"),
                            rs.getBigDecimal("totalAmount")
                        )
                    }
                }
                result = Audit(default, fallback)
            }
        }
        return result
    }

    private fun recordPayment(payment: Payment) {

        SQLiteManager.executeWriteWithLock("data/payments.db") { conn ->
            try {
                conn.autoCommit = false

                val sql = """
                    INSERT INTO payments (
                        correlation_id, amount, processed, processor, requested_at
                    ) VALUES (?, ?, ?, ?, ?)
                """.trimIndent()

                conn.prepareStatement(sql).use { ps ->
                    ps.setString(1, payment.order.correlationId)
                    ps.setString(2, payment.order.amount.toString())
                    ps.setBoolean(3, payment.processed)
                    ps.setString(4, payment.processor)
                    ps.setString(5, payment.order.requestedAt.toString())
                    ps.executeUpdate()
                }

                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    suspend fun pay(request: PaymentRequest) {

        lateinit var processor: String
        lateinit var processorURL: String

        try {
            chooseProcessor().let { (service, url) ->
                processor = service
                processorURL = url
            }
        }catch (e: Exception){
            logger.warn("[STAGE 1] - reprocessing: ${request.correlationId}")
            return queue(request)
        }

        try {
            val proof = send(processorURL, request)
            try {
                if (proof) {
                    recordPayment(Payment(order = request, processed = true, processor = processor))
                    logger.info("payment processed successfully: ${request.correlationId}")
                } else {
                    logger.warn("[STAGE 2] - reprocessing ${request.correlationId}")
                    return queue(request)

                }
            } catch (e: Exception) {
                logger.error("failed to record payment: ${request.correlationId}", e)
                if( proof) {
                    throw Exception("[critical] - a payment processed has not been stored")
                }
            }
        } catch (e: Exception) {
            logger.warn("payment processor failure: ${e.message}", e)
        }
    }

    private suspend fun send(url: String, request: PaymentRequest): Boolean {

        logger.info("calling payment processor: ${request.correlationId}")

        val response = client.post("${url}/payments") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        return response.status == HttpStatusCode.OK
    }

    private suspend fun chooseProcessor(): Pair<String, String> {

        val defaultURL = System.getenv("PAYMENT_PROCESSOR_DEFAULT_URL") ?: "http://payment-processor-default:8080"
        val fallbackURL = System.getenv("PAYMENT_PROCESSOR_FALLBACK_URL") ?: "http://payment-processor-fallback:8080"

        suspend fun checkHealth(url: String): Pair<Boolean, Long> {
            return try {
                val start = System.nanoTime()
                val response = client.get("$url/payments/service-health")
                val elapsed = (System.nanoTime() - start) / 1_000_000

                if (response.status == HttpStatusCode.OK) {
                    val health = response.body<ProcessorHealthResponse>()
                    if (!health.failing) {
                        return true to elapsed
                    }
                }
                false to elapsed
            } catch (e: Exception) {
                logger.warn("health check failed for $url: ${e.message}", e)
                false to Long.MAX_VALUE
            }
        }

        var defaultHealthy = false
        var defaultTime = Long.MAX_VALUE
        var fallbackHealthy = false
        var fallbackTime = Long.MAX_VALUE

        checkHealth(defaultURL).let { (healthy, time) ->
            if (healthy) {
                defaultHealthy = true
                defaultTime = time
                return@let
            }
        }
        checkHealth(fallbackURL).let { (healthy, time) ->
            if (healthy) {
                fallbackHealthy = true
                fallbackTime = time
                return@let
            }
        }

        return when {
            defaultHealthy && fallbackHealthy -> {
                if (defaultTime > fallbackTime ) {
                    logger.info("default is slower (${defaultTime}ms) than fallback (${fallbackTime}ms): using fallback processor!")
                    "fallback" to fallbackURL
                } else {
                    "default" to defaultURL
                }
            }
            defaultHealthy -> "default" to defaultURL
            fallbackHealthy -> "fallback" to fallbackURL
            else -> throw IllegalStateException("no healthy processor available")
        }
    }
}