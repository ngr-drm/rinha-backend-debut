package com.rinha.lib

import com.rinha.lib.Activities.confirm
import com.rinha.lib.Activities.getHealthierGateway
import com.rinha.lib.Activities.sendPayment
import com.rinha.providers.Http.client
import com.rinha.providers.Redis
import io.ktor.client.call.*
import io.ktor.client.request.*
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import redis.clients.jedis.params.SetParams
import java.util.*
import kotlin.math.pow

object WorkerScope {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    fun stop() { scope.cancel() }
}

object Health {
    private val logger = LoggerFactory.getLogger(Health::class.java)
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = WorkerScope.scope.launch {
            while (isActive) {
                try {
                    healthCheckLoop()
                } catch (e: Exception) {
                    logger.warn("health check error: ${e.message}")
                }
                delay(5_000)
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    private suspend fun healthCheckLoop() {
        val defaultHealth = fetchHealth(Activities.defaultURL)
        val fallbackHealth = fetchHealth(Activities.fallbackURL)

        val checkedAt = System.currentTimeMillis() / 1000.0
        val (url, name) = when {
            defaultHealth.failing -> {
                Pair(Activities.fallbackURL, "fallback")
            }
            defaultHealth.minResponseTime < 120 -> {
                Pair(Activities.defaultURL, "default")
            }
            !fallbackHealth.failing && fallbackHealth.minResponseTime < defaultHealth.minResponseTime * 3 -> {
                Pair(Activities.fallbackURL, "fallback")
            }
            else -> {
                Pair(Activities.defaultURL, "default")
            }
        }

        val cacheData = """{"data":["$url","$name"],"ts":$checkedAt}"""
        Redis.withJedis { jedis ->
            jedis.set("gateway_status", cacheData)
            jedis.expire("gateway_status", 10)
        }
    }

    private suspend fun fetchHealth(url: String): ProcessorHealthResponse {
        return try {
            val response = client.get("$url/payments/service-health")
            if (response.status.value != 200) {
                ProcessorHealthResponse(failing = true, minResponseTime = 10_000)
            } else {
                response.body()
            }
        } catch (e: Exception) {
            logger.warn("Health check failed for $url: ${e.message}")
            ProcessorHealthResponse(failing = true, minResponseTime = 10_000)
        }
    }
}

object Inbound {
    private val logger = LoggerFactory.getLogger(Inbound::class.java)
    private val queue = Queue<QueuedPayment>()
    private val maxWorkers = System.getenv("MAX_CONCURRENT_PAYMENTS")?.toIntOrNull() ?: 8

    @Volatile private var started = false

    fun start() {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true

            repeat(maxWorkers) { workerId ->
                WorkerScope.scope.launch {
                    dequeue(workerId)
                }
            }
        }
    }

    private suspend fun dequeue(workerId: Int) {
        while (true) {
            try {
                val item = queue.get()
                pay(item, workerId)
            } catch (e: Exception) {
                logger.error("worker-$workerId general error: ${e.message}")
                delay(100)
            }
        }
    }

    private suspend fun pay(queued: QueuedPayment, workerId: Int) {
        val request = queued.request
        val attempts = queued.retries

        val lockResult = Redis.withJedis { jedis ->
            jedis.eval(
                """
                if redis.call('EXISTS', KEYS[1]) == 1 then
                    return 'already_paid'
                end
                if redis.call('SET', KEYS[2], '1', 'NX', 'EX', 60) then
                    return 'acquired'
                end
                return 'locked'
                """.trimIndent(),
                listOf("paid:${request.correlationId}", "lock:${request.correlationId}"),
                emptyList()
            ) as String
        }

        when (lockResult) {
            "already_paid" -> return
            "locked" -> {
                if (attempts < MAX_RETRIES) {
                    delay(100)
                    queue.put(QueuedPayment(queued.request, attempts + 1))
                }
                return
            }
        }

        try {
            val (gatewayUrl, gatewayName) = getHealthierGateway()
            val (success, shouldConfirm) = sendPayment(gatewayUrl, request)

            if (success && shouldConfirm) {
                confirm(request, gatewayName)
                return
            }

            if (success) {
                Redis.withJedis { it.del("lock:${request.correlationId}") }
                return
            }

            throw Exception("payment failed")
        } catch (e: Exception) {
            Redis.withJedis { it.del("lock:${request.correlationId}") }

            if (attempts + 1 >= MAX_RETRIES) {
                logger.error("[PERMANENT_FAILURE] worker-$workerId: ${request.correlationId} after ${attempts + 1} attempts")
                return
            }

            val backoffMs = (500L * 2.0.pow(attempts.toDouble())).toLong().coerceAtMost(3000L)
            delay(backoffMs)
            try {
                queue.put(QueuedPayment(queued.request, attempts + 1))
            } catch (queueError: Exception) {
                logger.error("[QUEUE_ERROR] worker-$workerId: ${request.correlationId} - ${queueError.message}")
            }
        }
    }

    fun enqueue(payment: PaymentRequest): Boolean =
        queue.trySend(QueuedPayment(payment))
}

object LeaderElection {
    private val logger = LoggerFactory.getLogger(LeaderElection::class.java)

    private val instanceId = UUID.randomUUID().toString()
    private const val LOCK_KEY = "leader_lock"
    private const val LOCK_TTL = 5L
    private const val RENEW_MS = 3_000L

    fun start() {
        WorkerScope.scope.launch {
            while (isActive) {
                try {
                    if (tryAcquireLock()) {
                        logger.info("Leadership acquired")
                        Health.start()
                        while (isStillLeader()) {
                            renewLock()
                            delay(RENEW_MS)
                        }
                        logger.warn("Leadership lost")
                        Health.stop()
                    } else {
                        delay(RENEW_MS)
                    }
                } catch (e: Exception) {
                    logger.error("Leader election error: ${e.message}")
                    delay(RENEW_MS)
                }
            }
        }
    }

    private suspend fun tryAcquireLock(): Boolean =
        Redis.withJedis { jedis ->
            val result = jedis.set(LOCK_KEY, instanceId, SetParams().nx().ex(LOCK_TTL))
            result == "OK"
        }

    private suspend fun isStillLeader(): Boolean =
        Redis.withJedis { jedis -> jedis.get(LOCK_KEY) == instanceId }

    private suspend fun renewLock() {
        Redis.withJedis { jedis ->
            if (jedis.get(LOCK_KEY) == instanceId) {
                jedis.expire(LOCK_KEY, LOCK_TTL)
            }
        }
    }
}

