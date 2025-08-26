package com.rinha

import com.rinha.lib.Activities.queue
import com.rinha.lib.Activities.toAudit
import com.rinha.lib.PaymentRequest
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.Instant
import java.util.UUID

fun Application.configureRouting() {
    routing {
        get("/health") {
            call.respondText("api is healthy!")
        }

        post("/payments") {
            val body = call.receive<PaymentRequest>()
            val uuid = UUID.fromString(body.correlationId)
            val requestedAt = Instant.now()

            val payment = PaymentRequest(
                correlationId = uuid.toString(),
                amount = body.amount,
                requestedAt = requestedAt.toString()
            )

            queue(payment)

            call.respond(io.ktor.http.HttpStatusCode.NoContent)
        }

        get("/payments-summary") {
            val from = call.request.queryParameters["from"]!!
            val to = call.request.queryParameters["to"]!!

            val summary = toAudit(from, to)
            call.respond(io.ktor.http.HttpStatusCode.OK, summary)
        }

    }
}

