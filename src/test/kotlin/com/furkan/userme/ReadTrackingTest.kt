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
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * `GET /me` uygulamanin her acilista attigi istektir: son gorulmeyi tazeler,
 * yeterince sessiz gecmisse acilis sayar, sorguda gelen profili gunceller.
 */
class ReadTrackingTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun config(dbName: String, izleme: ReadTracking? = ReadTracking(minWriteInterval = 5.minutes)) =
        UserMeConfig(
            database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver"),
            tablePrefix = "${dbName}_",
            accountId = { call -> call.request.headers["X-Account"]?.toIntOrNull() },
            readTracking = izleme
        ).also { it.migrate() }

    private fun ApplicationTestBuilder.setup(config: UserMeConfig) {
        application {
            install(ContentNegotiation) { json() }
            routing {
                userMeRoutes(config)
                sessionWriteRoutes(config)
            }
        }
    }

    private suspend fun ApplicationTestBuilder.session(
        deviceId: String,
        account: Int? = null,
        body: String = """{"deviceId":"$deviceId"}"""
    ): HttpResponse =
        client.post("/me/session") {
            contentType(ContentType.Application.Json)
            account?.let { header("X-Account", it.toString()) }
            setBody(body)
        }

    private suspend inline fun <reified T> HttpResponse.decode(): T = json.decodeFromString(bodyAsText())

    /** Kaydi gecmise cekerek "eski" hale getirir. */
    private fun eskit(config: UserMeConfig, dakika: Long) {
        val t = config.sessions
        transaction(config.database) {
            t.update { it[lastSeenAt] = LocalDateTime.now().minusMinutes(dakika) }
        }
    }

    private fun satir(config: UserMeConfig) = transaction(config.database) {
        config.sessions.selectAll().single()
    }

    private fun sonGorulme(config: UserMeConfig): LocalDateTime = satir(config)[config.sessions.lastSeenAt]

    @Test
    fun `kayit eskiyse me istegi son gorulmeyi tazeler`() = testApplication {
        val cfg = config("t1")
        setup(cfg)
        session("cihaz", account = 5)
        eskit(cfg, dakika = 60)
        val once = sonGorulme(cfg)

        val cevap = client.get("/me") { header("X-Account", "5") }.decode<UserListItem>()

        assertTrue(sonGorulme(cfg).isAfter(once), "son gorulme tazelendi")
        assertEquals(1, cevap.openCount, "acilis sayimi kapaliyken artmaz")
    }

    @Test
    fun `kayit yeniyse yazma yapilmaz`() = testApplication {
        val cfg = config("t2")
        setup(cfg)
        session("cihaz", account = 5)
        val once = sonGorulme(cfg)

        client.get("/me") { header("X-Account", "5") }

        assertEquals(once, sonGorulme(cfg), "esik dolmadan yazilmaz")
    }

    @Test
    fun `izleme kapaliysa hic tazelenmez`() = testApplication {
        val cfg = config("t3", izleme = null)
        setup(cfg)
        session("cihaz", account = 5)
        eskit(cfg, dakika = 60)
        val once = sonGorulme(cfg)

        client.get("/me") { header("X-Account", "5") }

        assertEquals(once, sonGorulme(cfg))
    }

    @Test
    fun `tokensiz cihaz sorgusunda da tazelenir`() = testApplication {
        val cfg = config("t4")
        setup(cfg)
        session("anonim-cihaz")
        eskit(cfg, dakika = 60)
        val once = sonGorulme(cfg)

        client.get("/me?deviceId=anonim-cihaz")

        assertTrue(sonGorulme(cfg).isAfter(once))
    }

    @Test
    fun `yeterince sessiz gecmisse okuma yeni acilis sayilir`() = testApplication {
        val cfg = config("t5", ReadTracking(minWriteInterval = 5.minutes, newOpenAfter = 30.minutes))
        setup(cfg)
        session("cihaz", account = 5)

        // Sessizlik suresi dolmadan: ayni acilis.
        eskit(cfg, dakika = 10)
        assertEquals(1, client.get("/me") { header("X-Account", "5") }.decode<UserListItem>().openCount)

        // Yarim saatten uzun sessizlik: yeni acilis.
        eskit(cfg, dakika = 45)
        assertEquals(2, client.get("/me") { header("X-Account", "5") }.decode<UserListItem>().openCount)
    }

    @Test
    fun `sorgudaki profil degistiyse hemen yazilir`() = testApplication {
        val cfg = config("t6")
        setup(cfg)
        session("cihaz", account = 5, body = """{"deviceId":"cihaz","appVersion":"1.0.0","language":"tr"}""")
        val once = sonGorulme(cfg)

        // Kayit yeni (esik dolmadi) ama surum degisti: yine de yazilir.
        val cevap = client.get("/me?appVersion=2.0.0&platform=android") { header("X-Account", "5") }
            .decode<UserListItem>()

        assertEquals("2.0.0", cevap.appVersion, "cevap guncel degeri doner")
        assertEquals("android", cevap.platform)
        assertEquals("tr", cevap.language, "gonderilmeyen alan korunur")
        assertTrue(sonGorulme(cfg).isAfter(once))
    }

    @Test
    fun `ayni profil tekrar gonderilirse yazma yapilmaz`() = testApplication {
        val cfg = config("t7")
        setup(cfg)
        session("cihaz", account = 5, body = """{"deviceId":"cihaz","appVersion":"1.0.0"}""")
        val once = sonGorulme(cfg)

        client.get("/me?appVersion=1.0.0") { header("X-Account", "5") }

        assertEquals(once, sonGorulme(cfg), "degismeyen profil UPDATE uretmez")
    }

    @Test
    fun `app_version takma adi ve konum da kabul edilir`() = testApplication {
        val cfg = config("t8")
        setup(cfg)
        session("anonim")

        val cevap = client.get("/me?deviceId=anonim&app_version=3.1.0&latitude=41.01&longitude=28.97&city=Istanbul")
            .decode<UserListItem>()

        assertEquals("3.1.0", cevap.appVersion)
        assertEquals("Istanbul", cevap.city)
        assertEquals(41.01, cevap.latitude)
        assertEquals(28.97, cevap.longitude)
    }
}
