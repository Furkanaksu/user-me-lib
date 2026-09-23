package com.furkan.userme

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class UserMeTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** Test icin "giris": X-Account header'i varsa o hesap kabul edilir. */
    private fun config(dbName: String) = UserMeConfig(
        database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver"),
        tablePrefix = "${dbName}_",
        accountId = { call -> call.request.headers["X-Account"]?.toIntOrNull() }
    ).also { it.migrate() }

    private fun ApplicationTestBuilder.setup(config: UserMeConfig) {
        application {
            install(ContentNegotiation) { json() }
            routing { userMeRoutes(config) }
        }
    }

    private suspend fun ApplicationTestBuilder.session(body: String, account: Int? = null): HttpResponse =
        client.post("/me/session") {
            contentType(ContentType.Application.Json)
            account?.let { header("X-Account", it.toString()) }
            setBody(body)
        }

    private suspend inline fun <reified T> HttpResponse.decode(): T = json.decodeFromString(bodyAsText())

    @Test
    fun `giris yoksa oturum cihaza baglanir ve her acilista guncellenir`() = testApplication {
        setup(config("u1"))

        val first = session(
            """{"deviceId":"cihaz-1","platform":"android","appVersion":"1.0.0","city":"Istanbul",
               "metadata":{"tema":"koyu"}}"""
        ).decode<SessionResponse>()
        assertNull(first.accountId)
        assertEquals(1, first.openCount)

        val second = session("""{"deviceId":"cihaz-1","appVersion":"1.1.0","metadata":{"premium":true}}""")
            .decode<SessionResponse>()
        assertEquals(first.id, second.id)
        assertEquals(2, second.openCount)
        assertEquals("1.1.0", second.appVersion)
        assertEquals("Istanbul", second.city, "gonderilmeyen alan korunur")
        assertEquals("koyu", second.metadata["tema"]!!.jsonPrimitive.content)
        assertEquals("true", second.metadata["premium"]!!.jsonPrimitive.content)
    }

    @Test
    fun `mevcut istemci formati kabul edilir`() = testApplication {
        setup(config("u2"))
        val response = session(
            """{"deviceId":"eski","platform":"ios","app_version":"2.3.0","app_name":"prayapp",
               "latitude":"41.0082","longitude":"28.9784"}"""
        )
        assertEquals(HttpStatusCode.OK, response.status)
        val s = response.decode<SessionResponse>()
        assertEquals("2.3.0", s.appVersion)
        assertEquals("prayapp", s.appName)
        assertEquals(41.0082, s.latitude)
    }

    @Test
    fun `deviceId zorunlu`() = testApplication {
        setup(config("u3"))
        assertEquals(HttpStatusCode.BadRequest, session("""{"platform":"android"}""").status)
        assertEquals(HttpStatusCode.BadRequest, session("bozuk json").status)
    }

    @Test
    fun `giris yapinca cihazin anonim oturumu hesaba devredilir`() = testApplication {
        val cfg = config("u4")
        setup(cfg)

        session("""{"deviceId":"cihaz-A","platform":"android"}""")
        val anon = session("""{"deviceId":"cihaz-A"}""").decode<SessionResponse>()
        assertEquals(2, anon.openCount)

        val linked = session("""{"deviceId":"cihaz-A"}""", account = 7).decode<SessionResponse>()
        assertEquals(anon.id, linked.id, "ayni satir hesaba baglandi")
        assertEquals(7, linked.accountId)
        assertEquals(3, linked.openCount)
        assertEquals("android", linked.platform)
        assertEquals(0, anonymousCount(cfg))
    }

    @Test
    fun `giris varsa oturum hesaba aittir, cihaz degisince ayni oturum devam eder`() = testApplication {
        val cfg = config("u5")
        setup(cfg)

        val onA = session("""{"deviceId":"telefon","platform":"android"}""", account = 9)
            .decode<SessionResponse>()

        session("""{"deviceId":"tablet","metadata":{"tabletOnly":true}}""")
        session("""{"deviceId":"tablet"}""")

        val onB = session("""{"deviceId":"tablet","platform":"ios"}""", account = 9).decode<SessionResponse>()
        assertEquals(onA.id, onB.id, "hesap basina tek oturum")
        assertEquals("tablet", onB.deviceId)
        assertEquals("ios", onB.platform)
        assertEquals(1 + 2 + 1, onB.openCount, "tabletin anonim acilislari hesaba eklendi")
        assertEquals("true", onB.metadata["tabletOnly"]!!.jsonPrimitive.content)
        assertEquals(0, anonymousCount(cfg))
        assertEquals(1, totalCount(cfg))
    }

    @Test
    fun `me ucu giris varsa hesabin, yoksa cihazin bilgilerini doner`() = testApplication {
        setup(config("u6"))
        session("""{"deviceId":"cihaz-X","appName":"dizibook"}""")
        session("""{"deviceId":"cihaz-Y"}""", account = 3)

        val byDevice = client.get("/me?deviceId=cihaz-X").decode<UserListItem>()
        assertEquals("dizibook", byDevice.appName)
        assertNull(byDevice.accountId)

        val byAccount = client.get("/me") { header("X-Account", "3") }.decode<UserListItem>()
        assertEquals(3, byAccount.accountId)

        assertEquals(HttpStatusCode.BadRequest, client.get("/me").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/me?deviceId=yok").status)
    }

    @Test
    fun `koddan da kullanilabilir`() {
        val cfg = config("u7")
        val sessions = UserMeSessions(cfg)

        val created = sessions.upsert(SessionRequest(deviceId = "kod", language = "tr"))
        assertEquals("tr", created.language)
        assertEquals("tr", sessions.findByDevice("kod")?.language)
        assertNull(sessions.findByDevice("olmayan"))
        assertFailsWith<IllegalArgumentException> { sessions.upsert(SessionRequest(deviceId = " ")) }
    }

    private fun anonymousCount(config: UserMeConfig): Long = transaction(config.database) {
        config.sessions.selectAll().where { config.sessions.accountId.isNull() }.count()
    }

    private fun totalCount(config: UserMeConfig): Long = transaction(config.database) {
        config.sessions.selectAll().count()
    }
}
