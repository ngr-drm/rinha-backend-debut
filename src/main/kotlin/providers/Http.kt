package com.rinha.providers

import com.rinha.BigDecimalSerializer
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import java.math.BigDecimal

object Http {

    val client = HttpClient(CIO) {

        install(ContentNegotiation) {
            json(
                Json {
                    serializersModule = SerializersModule {
                        contextual(BigDecimal::class, BigDecimalSerializer)
                    }
                    prettyPrint    = false
                    isLenient      = true
                    ignoreUnknownKeys = true
                }
            )
        }

        engine {
            endpoint {
                connectTimeout         = 10_000
                requestTimeout         = 10_000
                keepAliveTime          = 30_000
                pipelineMaxSize        = 10
                maxConnectionsPerRoute = 50
                maxConnectionsCount    = 100
            }
        }
    }
}