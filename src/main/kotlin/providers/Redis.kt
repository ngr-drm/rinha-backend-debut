package com.rinha.providers

import redis.clients.jedis.Jedis
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPoolConfig

object RedisClient {

    private var pool: JedisPool? = null

    fun initialize() {
        val host = System.getenv("REDIS_HOST") ?: "localhost"
        val port = System.getenv("REDIS_PORT")?.toIntOrNull() ?: 6379

        val config = JedisPoolConfig().apply {
            maxTotal = 20
            maxIdle = 10
            minIdle = 2
            testOnBorrow = true
            testWhileIdle = true
        }

        pool = JedisPool(config, host, port)
    }

    suspend fun <T> withJedis(block: suspend (Jedis) -> T) {
        pool?.resource?.use { jedis ->
            block(jedis)
        }
    }

    fun close() {
        pool?.close()
    }
}