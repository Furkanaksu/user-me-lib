package com.furkan.userme

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Projeye ozel tablolar: kutuphane bunlarin varligini bilmez, parametre olarak alir. */
private class TestFlags(name: String) : IntIdTable(name) {
    val deviceId = varchar("device_id", 255).uniqueIndex()
    val isPremium = bool("is_premium").default(false)
    val premiumExpiryDate = varchar("premium_expiry_date", 50).nullable()
    val locationSource = varchar("location_source", 50).nullable()
}

private class TestTokens(name: String) : IntIdTable(name) {
    val deviceId = varchar("device_id", 255).uniqueIndex()
    val token = varchar("token", 255)
}

class ExtraSourceTest {

    private val json = Json { ignoreUnknownKeys = true }

    private class Kurulum(val config: UserMeConfig, val flags: TestFlags, val tokens: TestTokens)

    private fun kur(dbName: String): Kurulum {
        val db = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        val flags = TestFlags("${dbName}_flags")
        val tokens = TestTokens("${dbName}_tokens")
        transaction(db) { SchemaUtils.create(flags, tokens) }

        val config = UserMeConfig(
            database = db,
            tablePrefix = "${dbName}_",
            extras = listOf(
                ExtraSource(
                    table = flags,
                    foreignKey = flags.deviceId,
                    joinKey = SessionJoinKey.DEVICE_ID,
                    columns = mapOf(
                        "isPremium" to flags.isPremium,
                        "premiumExpiryDate" to flags.premiumExpiryDate,
                        "locationSource" to flags.locationSource
                    )
                ),
                ExtraSource(
                    table = tokens,
                    foreignKey = tokens.deviceId,
                    joinKey = SessionJoinKey.DEVICE_ID,
                    columns = mapOf("fcmToken" to tokens.token)
                )
            )
        ).also { it.migrate() }

        return Kurulum(config, flags, tokens)
    }

    private fun ApplicationTestBuilder.setup(config: UserMeConfig) {
        application {
            install(ContentNegotiation) { json() }
            routing {
                userMeRoutes(config)
                sessionWriteRoutes(config)
                userListRoutes(config)
            }
        }
    }

    private suspend fun ApplicationTestBuilder.session(deviceId: String, language: String = "tr"): HttpResponse =
        client.post("/me/session") {
            contentType(ContentType.Application.Json)
            setBody("""{"deviceId":"$deviceId","language":"$language","appName":"prayapp"}""")
        }

    private suspend inline fun <reified T> HttpResponse.decode(): T = json.decodeFromString(bodyAsText())

    private fun flag(k: Kurulum, deviceId: String, premium: Boolean, expiry: String? = null, source: String? = null) =
        transaction(k.config.database) {
            k.flags.insert {
                it[this.deviceId] = deviceId
                it[this.isPremium] = premium
                it[this.premiumExpiryDate] = expiry
                it[this.locationSource] = source
            }
        }

    private fun token(k: Kurulum, deviceId: String, value: String) = transaction(k.config.database) {
        k.tokens.insert {
            it[this.deviceId] = deviceId
            it[this.token] = value
        }
    }

    @Test
    fun `projeye ozel alanlar cevapta extras altinda doner`() = testApplication {
        val k = kur("e1")
        setup(k.config)
        session("cihaz-1")
        flag(k, "cihaz-1", premium = true, expiry = "2027-01-01", source = "gps")
        token(k, "cihaz-1", "fcm-abc")

        val liste = client.get("/users").decode<PaginatedUsersResponse>()
        val satir = liste.data.single()

        assertEquals("true", satir.extras["isPremium"]!!.jsonPrimitive.content)
        assertEquals("2027-01-01", satir.extras["premiumExpiryDate"]!!.jsonPrimitive.content)
        assertEquals("gps", satir.extras["locationSource"]!!.jsonPrimitive.content)
        assertEquals("fcm-abc", satir.extras["fcmToken"]!!.jsonPrimitive.content)
    }

    @Test
    fun `ek tabloda satiri olmayan cihazda alanlar null doner`() = testApplication {
        val k = kur("e2")
        setup(k.config)
        session("bayraksiz-cihaz")

        val satir = client.get("/users").decode<PaginatedUsersResponse>().data.single()
        assertTrue(satir.extras.containsKey("isPremium"), "alan yine de cevapta yer alir")
        assertEquals("null", satir.extras["isPremium"].toString())
        assertEquals("null", satir.extras["fcmToken"].toString())
    }

    @Test
    fun `projeye ozel alanlarla filtrelenebilir`() = testApplication {
        val k = kur("e3")
        setup(k.config)
        session("premium-cihaz")
        session("normal-cihaz")
        flag(k, "premium-cihaz", premium = true, source = "gps")
        flag(k, "normal-cihaz", premium = false, source = "ip")

        assertEquals(2, client.get("/users").decode<PaginatedUsersResponse>().totalItems)

        val premiumlar = client.get("/users?isPremium=true").decode<PaginatedUsersResponse>()
        assertEquals(1, premiumlar.totalItems)
        assertEquals("premium-cihaz", premiumlar.data.single().deviceId)

        val normaller = client.get("/users?isPremium=false").decode<PaginatedUsersResponse>()
        assertEquals(1, normaller.totalItems)

        val gps = client.get("/users?locationSource=gps").decode<PaginatedUsersResponse>()
        assertEquals("premium-cihaz", gps.data.single().deviceId)

        // Kutuphanenin kendi filtreleriyle birlikte calisir.
        val birlikte = client.get("/users?isPremium=true&language=tr&appName=prayapp")
            .decode<PaginatedUsersResponse>()
        assertEquals(1, birlikte.totalItems)
    }

    @Test
    fun `bilinmeyen filtre adi yok sayilir`() = testApplication {
        val k = kur("e4")
        setup(k.config)
        session("cihaz")

        assertEquals(1, client.get("/users?boyleBirAlanYok=123").decode<PaginatedUsersResponse>().totalItems)
    }

    @Test
    fun `son N gun filtresi`() = testApplication {
        val k = kur("e5")
        setup(k.config)
        session("yeni-cihaz")

        assertEquals(1, client.get("/users?days=7").decode<PaginatedUsersResponse>().totalItems)
        // Gecmise tarihli bir satir: filtre disinda kalmali.
        val oturumlar = k.config.sessions
        transaction(k.config.database) {
            oturumlar.update {
                it[oturumlar.lastSeenAt] = java.time.LocalDateTime.now().minusDays(30)
            }
        }
        assertEquals(0, client.get("/users?days=7").decode<PaginatedUsersResponse>().totalItems)
        assertEquals(1, client.get("/users").decode<PaginatedUsersResponse>().totalItems)
    }

    @Test
    fun `extras verilmezse cevap bos nesne doner`() = testApplication {
        val db = Database.connect("jdbc:h2:mem:e6;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        val config = UserMeConfig(database = db, tablePrefix = "e6_").also { it.migrate() }
        setup(config)
        session("cihaz")

        val satir = client.get("/users").decode<PaginatedUsersResponse>().data.single()
        assertTrue(satir.extras.isEmpty())
    }
}
