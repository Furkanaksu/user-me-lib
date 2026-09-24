package com.furkan.userme

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlin.time.toJavaDuration

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
 * - `GET {basePath}`  benim bilgilerim (giris varsa hesap + oturum, yoksa `?deviceId=`).
 *   Uygulamanin her acilista attigi istek budur: [UserMeConfig.readTracking] doluysa ayni
 *   istek son gorulmeyi tazeler, acilisi sayar ve sorguda gelen profil alanlarini gunceller
 *   (`?appVersion=2.3.0&language=tr`).
 *
 * Ayri monte edilenler: oturum yazma ucu [sessionWriteRoutes], kullanici listesi [userListRoutes].
 */
fun Route.userMeRoutes(config: UserMeConfig) {
    val handlers = UserMeHandlers(config, UserMeSessions(config), UserMeUsers(config))

    route(config.basePath) {
        get { handlers.me(call) }
    }
}

/**
 * Oturum yazma ucu: `POST {basePath}/session`.
 *
 * AYRI monte edilir, cunku her projeye gerekmez. Girisi auth-lib ile yapan projeler oturumu
 * zaten giris aninda yaziyor (giris olayinda [UserMeSessions.upsert] cagrilarak), bu ucu hic
 * monte etmez. Kendi giris akisi olmayan projeler icin ise hazir bir yazma ucudur.
 */
fun Route.sessionWriteRoutes(config: UserMeConfig) {
    val handlers = UserMeHandlers(config, UserMeSessions(config), UserMeUsers(config))

    route(config.basePath) {
        post("/session") { handlers.upsertSession(call) }
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
            return
        }

        // Bu uc uygulamanin her acilista attigi istektir; okuma ayni zamanda acilis sinyalidir.
        val izleme = config.readTracking
        if (izleme == null) {
            call.respond(user)
            return
        }

        val yazildi = users.touchOnRead(
            sessionId = user.sessionId,
            profile = call.profileUpdate(user),
            minWriteInterval = izleme.minWriteInterval.toJavaDuration(),
            newOpenAfter = izleme.newOpenAfter?.toJavaDuration()
        )
        call.respond(if (yazildi) users.findBySession(user.sessionId) ?: user else user)
    }

    /**
     * Sorgudan gelen profil alanlari; sadece kayittakinden FARKLI olanlar doner, hicbiri
     * degismediyse null. Boylece surum/dil degisiminde hemen yazilir, ayni degerler
     * her acilista bosuna UPDATE uretmez.
     */
    private fun ApplicationCall.profileUpdate(current: UserListItem): SessionRequest? {
        val p = request.queryParameters
        fun farkli(vararg adlar: String, eski: String?): String? =
            adlar.firstNotNullOfOrNull { p[it] }?.trim()?.takeIf { it.isNotEmpty() && it != eski }

        val guncel = SessionRequest(
            platform = farkli("platform", eski = current.platform),
            appVersion = farkli("appVersion", "app_version", eski = current.appVersion),
            appName = farkli("appName", "app_name", eski = current.appName),
            language = farkli("language", eski = current.language),
            city = farkli("city", eski = current.city),
            latitude = p["latitude"]?.toDoubleOrNull()?.takeIf { it != current.latitude },
            longitude = p["longitude"]?.toDoubleOrNull()?.takeIf { it != current.longitude }
        )
        val bosMu = listOf(
            guncel.platform, guncel.appVersion, guncel.appName, guncel.language,
            guncel.city, guncel.latitude, guncel.longitude
        ).all { it == null }
        return guncel.takeUnless { bosMu }
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

        // Projeye ozel kolon adlariyla gelen parametreler ek filtre olur (orn. ?isPremium=true).
        val bilinen = setOf("q", "language", "appVersion", "appName", "registered", "days", "page", "size")
        val extraFilters = params.entries()
            .filter { it.key !in bilinen }
            .mapNotNull { entry -> entry.value.firstOrNull()?.let { entry.key to it } }
            .toMap()

        val (items, total) = users.query(
            filter = UserQuery(
                q = params["q"],
                language = params["language"],
                appVersion = params["appVersion"],
                appName = params["appName"],
                registered = registered,
                days = params["days"]?.toIntOrNull(),
                extras = extraFilters
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
