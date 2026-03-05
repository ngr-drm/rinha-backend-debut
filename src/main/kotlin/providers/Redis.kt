package com.rinha.providers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import redis.clients.jedis.Jedis
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPoolConfig

object Redis {
    private lateinit var pool: JedisPool

    fun initialize() {
        val host = System.getenv("REDIS_HOST") ?: "localhost"
        val port = System.getenv("REDIS_PORT")?.toIntOrNull() ?: 6379

        val config = JedisPoolConfig().apply {
            maxTotal             = 100
            maxIdle              = 30
            minIdle              = 10
            testOnBorrow         = false
            testWhileIdle        = true
            blockWhenExhausted   = true
        }

        pool = JedisPool(config, host, port)
    }

    suspend fun <T> withJedis(block: suspend (Jedis) -> T): T =
        withContext(Dispatchers.IO) {
            pool.resource.use { jedis -> block(jedis) }
        }

    fun close() = pool.close()
}