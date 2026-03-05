package com.rinha

import com.rinha.lib.Activities.toAudit
import com.rinha.lib.Inbound.enqueue
import com.rinha.lib.PaymentRequest
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.Instant
import java.util.*

fun Application.configureRouting() {
    routing {
        get("/health") {
            call.respondText("api is healthy!")
        }

        post("/payments") {
            val body = call.receive<PaymentRequest>()
            val correlationId = runCatching { UUID.fromString(body.correlationId).toString() }
                .getOrElse {
                    call.respond(HttpStatusCode.BadRequest, "invalid correlationId")
                    return@post
                }


            val payment = PaymentRequest(
                correlationId = correlationId,
                amount        = body.amount,
                requestedAt   = Instant.now().toString()
            )

            if (enqueue(payment)) {
                call.respond(HttpStatusCode.Accepted)
            } else {
                call.respond(HttpStatusCode.TooManyRequests)
            }
        }

        get("/payments-summary") {
            val from = call.request.queryParameters["from"]
            val to   = call.request.queryParameters["to"]
            val summary = runCatching { toAudit(from, to) }.getOrElse {
                call.respond(HttpStatusCode.BadRequest, "invalid from/to format, expected ISO-8601")
                return@get
            }
            call.respond(HttpStatusCode.OK, summary)
        }
    }
}
