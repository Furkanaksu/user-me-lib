package com.furkan.userme

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * Kullanicinin kendine dair uclari.
 *
 * ```
 * val me = UserMeConfig(database = db, accountId = { it.currentAccount()?.accountId })
 * me.migrate()
 * routing { userMeRoutes(me) }
 * ```
 *
 * Uc noktalar (basePath'e gore, varsayilan `/me`):
 * - `POST {basePath}/session`  uygulama acilisinda oturum kaydi
 * - `GET  {basePath}`          benim oturumum (giris varsa hesabin, yoksa `?deviceId=` ile cihazin)
 */
fun Route.userMeRoutes(config: UserMeConfig) {
    val handlers = UserMeHandlers(config, UserMeSessions(config))

    route(config.basePath) {
        post("/session") { handlers.upsertSession(call) }
        get { handlers.me(call) }
    }
}

internal class UserMeHandlers(
    private val config: UserMeConfig,
    private val sessions: UserMeSessions
) {

    suspend fun upsertSession(call: ApplicationCall) {
        val request = try {
            call.receive<SessionRequest>()
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, UserMeErrorResponse(error = "Gecersiz istek govdesi"))
            return
        }

        val session = try {
            sessions.upsert(request, accountId = config.accountId(call))
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, UserMeErrorResponse(error = e.message ?: "Gecersiz istek"))
            return
        }
        call.respond(session)
    }

    /** Giris varsa hesabin oturumu; yoksa sorgudaki cihazin oturumu. */
    suspend fun me(call: ApplicationCall) {
        val accountId = config.accountId(call)
        val session = if (accountId != null) {
            sessions.findByAccount(accountId)
        } else {
            val deviceId = call.request.queryParameters["deviceId"]?.trim()
            if (deviceId.isNullOrBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    UserMeErrorResponse(error = "Giris yoksa deviceId parametresi gerekir")
                )
                return
            }
            sessions.findByDevice(deviceId)
        }

        if (session == null) {
            call.respond(HttpStatusCode.NotFound, UserMeErrorResponse(error = "Oturum bulunamadi"))
        } else {
            call.respond(session)
        }
    }
}
