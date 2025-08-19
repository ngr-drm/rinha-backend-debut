package com.rinha.lib

import com.rinha.providers.Http.client
import com.rinha.providers.RedisClient
import com.rinha.providers.SQLiteManager
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

object Activities {

    private val logger = LoggerFactory.getLogger(Activities::class.java)

    suspend fun queue(order: PaymentRequest){

        RedisClient.withJedis { jedis ->

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

    fun getPaymentsSummary(from: String, to: String): Audit {
        lateinit var result: Audit
        SQLiteManager.executeWithLock("data/payments.db") { conn ->
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

                var default = Summary(0, 0.00.toBigDecimal())
                var fallback = Summary(0, 0.00.toBigDecimal())

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
        SQLiteManager.executeWithLock("data/payments.db") { conn ->
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

    suspend fun makePayment(request: PaymentRequest): Boolean {
        val primaryUrl = System.getenv("PAYMENT_PROCESSOR_DEFAULT_URL") ?: "http://payment-processor-default:8080/payments"
        val fallbackUrl = System.getenv("PAYMENT_PROCESSOR_FALLBACK_URL") ?: "http://payment-processor-fallback:8080/payments"
        var proof = false

        try {
            proof = paymentProcessor(primaryUrl, request)
            recordPayment(Payment(order = request, processed = proof, processor = "default"))
            return proof

        } catch (e: Exception) {
            logger.warn("default flow failure: ${e.message}")
            try {
                proof = paymentProcessor(fallbackUrl, request)
                recordPayment(Payment(order = request, processed = proof, processor = "fallback"))
                return proof

            } catch (e: Exception) {
                logger.error("fallback flow failure: ${e.message}")
            }
        }
        return proof
    }

    private suspend fun paymentProcessor(url: String, request: PaymentRequest): Boolean {
        logger.info("boot payment processor: ${request.correlationId}")
        val response = client.post(url) {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        return response.status == HttpStatusCode.OK
    }

}