package com.rinha

import com.rinha.lib.*
import com.rinha.providers.Redis
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import java.math.BigDecimal

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.module() {

    install(ContentNegotiation) {
        json(
            Json {
                serializersModule = SerializersModule {
                    contextual(BigDecimal::class, BigDecimalSerializer)
                }
                prettyPrint = false
                isLenient = true
                ignoreUnknownKeys = true
            }
        )
    }

    Redis.initialize()

    LeaderElection.start()
    Inbound.start()

    Runtime.getRuntime().addShutdownHook(Thread {
        WorkerScope.stop()
        Redis.close()
    })

    configureRouting()
}

object BigDecimalSerializer : KSerializer<BigDecimal> {
    override val descriptor = PrimitiveSerialDescriptor("BigDecimal", PrimitiveKind.DOUBLE)
    override fun serialize(encoder: Encoder, value: BigDecimal) {
        encoder.encodeDouble(value.toDouble())
    }
    override fun deserialize(decoder: Decoder): BigDecimal {
        return BigDecimal(decoder.decodeDouble())
    }
}