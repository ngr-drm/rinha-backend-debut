package com.rinha.lib

import com.rinha.providers.Http.client
import com.rinha.providers.Redis
import io.ktor.client.request.*
import io.ktor.http.*
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Instant

object Activities {

    private val logger = LoggerFactory.getLogger(Activities::class.java)

    val defaultURL  = System.getenv("PAYMENT_PROCESSOR_DEFAULT_URL")  ?: "http://payment-processor-default:8080"
    val fallbackURL = System.getenv("PAYMENT_PROCESSOR_FALLBACK_URL") ?: "http://payment-processor-fallback:8080"

    private const val ZSET_KEY       = "payments_by_date"
    private const val CACHE_KEY      = "gateway_status"

    private data class CacheEntry(val data: Pair<String, String>, val timestamp: Long)
    @Volatile private var localCache: CacheEntry? = null

    suspend fun getHealthierGateway(): Pair<String, String> {
        val now = System.currentTimeMillis()

        localCache?.let {
            if (now - it.timestamp < 5000) {
                return it.data
            }
        }
        try {
            val cached = Redis.withJedis { jedis ->
                jedis.get(CACHE_KEY)
            }
            cached?.let {
                try {
                    val dataStart = it.indexOf("\"data\":[") + 8
                    val dataEnd = it.indexOf("]", dataStart)
                    val dataPart = it.substring(dataStart, dataEnd)

                    val urlStart = dataPart.indexOf("\"") + 1
                    val urlEnd = dataPart.indexOf("\"", urlStart)
                    val url = dataPart.substring(urlStart, urlEnd)

                    val nameStart = dataPart.indexOf("\"", urlEnd + 1) + 1
                    val nameEnd = dataPart.indexOf("\"", nameStart)
                    val name = dataPart.substring(nameStart, nameEnd)

                    localCache = CacheEntry(Pair(url, name), now)
                    return Pair(url, name)
                } catch (e: Exception) {
                    logger.warn("failed to parse cache: ${e.message}")
                }
            }
        } catch (e: Exception) {
            logger.warn("cache lookup failed: ${e.message}")
        }

        localCache = CacheEntry(Pair(defaultURL, "default"), now)
        return Pair(defaultURL, "default")
    }

    suspend fun sendPayment(url: String, request: PaymentRequest): Pair<Boolean, Boolean> {
        return try {
            val response = client.post("$url/payments") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            when (response.status) {
                HttpStatusCode.OK -> Pair(true, true)
                HttpStatusCode.UnprocessableEntity -> Pair(true, false)
                else -> Pair(false, false)
            }
        } catch (e: Exception) {
            logger.warn("Gateway error [$url] for ${request.correlationId}: ${e.message}")
            Pair(false, false)
        }
    }

    suspend fun confirm(order: PaymentRequest, processor: String) {
        Redis.withJedis { jedis ->
            val timestamp = Instant.parse(order.requestedAt!!).toEpochMilli() / 1000.0
            val paymentJson = """{"correlation_id":"${order.correlationId}","amount":${order.amount},"processor":"$processor","requested_at":$timestamp}"""

            jedis.eval(
                """
                redis.call('SET', KEYS[1], '1', 'EX', 3600)
                redis.call('ZADD', KEYS[2], ARGV[1], ARGV[2])
                redis.call('DEL', KEYS[3])
                return 1
                """.trimIndent(),
                listOf("paid:${order.correlationId}", ZSET_KEY, "lock:${order.correlationId}"),
                listOf(timestamp.toString(), paymentJson)
            )
        }
    }

    suspend fun purgePayments() {
        Redis.withJedis { jedis ->
            jedis.del(ZSET_KEY)
            val paidKeys = jedis.keys("paid:*")
            if (paidKeys.isNotEmpty()) {
                jedis.del(*paidKeys.toTypedArray())
            }
            val lockKeys = jedis.keys("lock:*")
            if (lockKeys.isNotEmpty()) {
                jedis.del(*lockKeys.toTypedArray())
            }
        }
    }

    suspend fun toAudit(from: String?, to: String?): Audit =
        Redis.withJedis { jedis ->
            val fromTs = from
                ?.takeIf { it.isNotBlank() }
                ?.let { Instant.parse(it).toEpochMilli() / 1000.0 }
                ?: Double.NEGATIVE_INFINITY
            val toTs   = to
                ?.takeIf { it.isNotBlank() }
                ?.let { Instant.parse(it).toEpochMilli() / 1000.0 }
                ?: Double.POSITIVE_INFINITY

            val paymentJsons = jedis.zrangeByScore(ZSET_KEY, fromTs, toTs)
            if (paymentJsons.isEmpty()) {
                return@withJedis Audit(Summary(0, BigDecimal.ZERO), Summary(0, BigDecimal.ZERO))
            }

            var dCount = 0; var dSum = BigDecimal.ZERO
            var fCount = 0; var fSum = BigDecimal.ZERO

            for (json in paymentJsons) {
                val processorMatch = json.substringAfter("\"processor\":\"").substringBefore("\"")
                val amountMatch = json.substringAfter("\"amount\":").substringBefore(",")

                if (processorMatch.isNotEmpty() && amountMatch.isNotEmpty()) {
                    val amount = amountMatch.toBigDecimalOrNull() ?: continue
                    when (processorMatch) {
                        "default"  -> { dCount++; dSum = dSum.add(amount) }
                        "fallback" -> { fCount++; fSum = fSum.add(amount) }
                    }
                }
            }

            Audit(
                Summary(dCount, dSum.setScale(1, java.math.RoundingMode.HALF_UP)),
                Summary(fCount, fSum.setScale(1, java.math.RoundingMode.HALF_UP))
            )
        }
}
