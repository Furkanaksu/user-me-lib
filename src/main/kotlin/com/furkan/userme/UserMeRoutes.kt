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
 * - `GET  {basePath}`          benim bilgilerim (giris varsa hesap + oturum, yoksa `?deviceId=`)
 *
 * Kayitli kullanicilarin listesi ayri monte edilir: [userListRoutes].
 */
fun Route.userMeRoutes(config: UserMeConfig) {
    val handlers = UserMeHandlers(config, UserMeSessions(config), UserMeUsers(config))

    route(config.basePath) {
        post("/session") { handlers.upsertSession(call) }
        get { handlers.me(call) }
    }
}

/**
 * Kayitli kullanicilarin listesi. Herkesin email'ini donduren bir uctur; bu yuzden AYRI
 * monte edilir ve korumasi projeye birakilir:
 *
 * ```
 * authenticate("admin") { userListRoutes(me) }
 * ```
 *
 * Uc noktalar (usersPath'e gore, varsayilan `/users`):
 * - `GET {usersPath}`          sayfali liste — `q`, `language`, `appVersion`, `appName`,
 *                              `registered`, `page`, `size`
 * - `GET {usersPath}/filters`  dropdown degerleri
 */
fun Route.userListRoutes(config: UserMeConfig) {
    val handlers = UserMeHandlers(config, UserMeSessions(config), UserMeUsers(config))

    route(config.usersPath) {
        get { handlers.listUsers(call) }
        get("/filters") { handlers.filterOptions(call) }
    }
}

internal class UserMeHandlers(
    private val config: UserMeConfig,
    private val sessions: UserMeSessions,
    private val users: UserMeUsers
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

    /** Giris varsa hesabin bilgileri; yoksa sorgudaki cihazin bilgileri. */
    suspend fun me(call: ApplicationCall) {
        val accountId = config.accountId(call)
        val user = if (accountId != null) {
            users.findByAccount(accountId)
        } else {
            val deviceId = call.request.queryParameters["deviceId"]?.trim()
            if (deviceId.isNullOrBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    UserMeErrorResponse(error = "Giris yoksa deviceId parametresi gerekir")
                )
                return
            }
            users.findByDevice(deviceId)
        }

        if (user == null) {
            call.respond(HttpStatusCode.NotFound, UserMeErrorResponse(error = "Kullanici bulunamadi"))
        } else {
            call.respond(user)
        }
    }

    suspend fun listUsers(call: ApplicationCall) {
        val params = call.request.queryParameters
        val page = params["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val size = (params["size"]?.toIntOrNull() ?: config.defaultPageSize).coerceIn(1, config.maxPageSize)

        val registered = when (params["registered"]?.lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }

        val (items, total) = users.query(
            filter = UserQuery(
                q = params["q"],
                language = params["language"],
                appVersion = params["appVersion"],
                appName = params["appName"],
                registered = registered
            ),
            page = page,
            size = size
        )

        call.respond(
            PaginatedUsersResponse(
                data = items,
                page = page,
                size = size,
                totalItems = total,
                totalPages = if (total == 0L) 1 else ((total + size - 1) / size).toInt()
            )
        )
    }

    suspend fun filterOptions(call: ApplicationCall) {
        call.respond(users.filterOptions())
    }
}
