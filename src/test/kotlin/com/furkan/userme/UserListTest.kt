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
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Hesap tablosu bu kutuphanenin disinda; testte auth-lib yerine kendi tablomuzu kuruyoruz. */
private class TestAccounts(name: String) : IntIdTable(name) {
    val email = varchar("email", 255).nullable()
    val displayName = varchar("display_name", 100).nullable()
}

class UserListTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun setupDb(dbName: String, withAccounts: Boolean = true): Pair<UserMeConfig, TestAccounts> {
        val db = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        val accounts = TestAccounts("${dbName}_accounts")
        transaction(db) { SchemaUtils.create(accounts) }

        val config = UserMeConfig(
            database = db,
            tablePrefix = "${dbName}_",
            accountId = { call -> call.request.headers["X-Account"]?.toIntOrNull() },
            accounts = if (withAccounts) {
                AccountsSource(accounts, accounts.id, accounts.email, accounts.displayName)
            } else {
                null
            }
        ).also { it.migrate() }

        return config to accounts
    }

    private fun account(config: UserMeConfig, accounts: TestAccounts, email: String, name: String? = null): Int =
        transaction(config.database) {
            accounts.insert {
                it[this.email] = email
                it[this.displayName] = name
            }[accounts.id].value
        }

    private fun ApplicationTestBuilder.setup(config: UserMeConfig) {
        application {
            install(ContentNegotiation) { json() }
            routing {
                userMeRoutes(config)
                userListRoutes(config)
            }
        }
    }

    private suspend fun ApplicationTestBuilder.session(body: String, account: Int? = null): HttpResponse =
        client.post("/me/session") {
            contentType(ContentType.Application.Json)
            account?.let { header("X-Account", it.toString()) }
            setBody(body)
        }

    private suspend inline fun <reified T> HttpResponse.decode(): T = json.decodeFromString(bodyAsText())

    /** Uc kullanici: ikisi kayitli (hesapli), biri anonim cihaz. */
    private suspend fun ApplicationTestBuilder.seed(config: UserMeConfig, accounts: TestAccounts): List<Int> {
        val ali = account(config, accounts, "ali@ornek.com", "Ali")
        val veli = account(config, accounts, "veli@baska.com", "Veli")
        session("""{"deviceId":"cihaz-ali","language":"tr","appVersion":"1.0.0","appName":"prayapp"}""", ali)
        session("""{"deviceId":"cihaz-veli","language":"en","appVersion":"2.0.0","appName":"dizibook"}""", veli)
        session("""{"deviceId":"cihaz-anonim","language":"tr","appVersion":"1.0.0","appName":"prayapp"}""")
        return listOf(ali, veli)
    }

    @Test
    fun `liste sayfali doner ve hesap bilgisi eklenir`() = testApplication {
        val (config, accounts) = setupDb("l1")
        setup(config)
        val (ali, _) = seed(config, accounts).let { it[0] to it[1] }

        val all = client.get("/users").decode<PaginatedUsersResponse>()
        assertEquals(3, all.totalItems)
        assertEquals(1, all.totalPages)

        val aliRow = all.data.single { it.accountId == ali }
        assertEquals("ali@ornek.com", aliRow.email)
        assertEquals("Ali", aliRow.displayName)
        assertEquals("cihaz-ali", aliRow.deviceId)

        val anonim = all.data.single { it.accountId == null }
        assertNull(anonim.email, "anonim cihazin hesabi yok")

        val firstPage = client.get("/users?page=1&size=2").decode<PaginatedUsersResponse>()
        assertEquals(2, firstPage.data.size)
        assertEquals(2, firstPage.totalPages)
        assertEquals(3, firstPage.totalItems)

        val secondPage = client.get("/users?page=2&size=2").decode<PaginatedUsersResponse>()
        assertEquals(1, secondPage.data.size)
    }

    @Test
    fun `arama email deviceId ve id ile calisir`() = testApplication {
        val (config, accounts) = setupDb("l2")
        setup(config)
        val ali = seed(config, accounts)[0]

        val byEmail = client.get("/users?q=ORNEK.com").decode<PaginatedUsersResponse>()
        assertEquals(1, byEmail.totalItems)
        assertEquals("ali@ornek.com", byEmail.data.single().email)

        val byDevice = client.get("/users?q=anonim").decode<PaginatedUsersResponse>()
        assertEquals(1, byDevice.totalItems)
        assertEquals("cihaz-anonim", byDevice.data.single().deviceId)

        val byAccountId = client.get("/users?q=$ali").decode<PaginatedUsersResponse>()
        assertTrue(byAccountId.data.any { it.accountId == ali }, "kullanici id'si ile de bulunur")

        assertEquals(0, client.get("/users?q=bulunmayan").decode<PaginatedUsersResponse>().totalItems)
    }

    @Test
    fun `dil surum ve uygulama filtreleri`() = testApplication {
        val (config, accounts) = setupDb("l3")
        setup(config)
        seed(config, accounts)

        assertEquals(2, client.get("/users?language=tr").decode<PaginatedUsersResponse>().totalItems)
        assertEquals(1, client.get("/users?appVersion=2.0.0").decode<PaginatedUsersResponse>().totalItems)
        assertEquals(2, client.get("/users?appName=prayapp").decode<PaginatedUsersResponse>().totalItems)

        // Filtreler birlikte calisir.
        val combined = client.get("/users?language=tr&appName=prayapp&appVersion=1.0.0")
            .decode<PaginatedUsersResponse>()
        assertEquals(2, combined.totalItems)

        assertEquals(2, client.get("/users?registered=true").decode<PaginatedUsersResponse>().totalItems)
        assertEquals(1, client.get("/users?registered=false").decode<PaginatedUsersResponse>().totalItems)
    }

    @Test
    fun `filtre secenekleri listelenir`() = testApplication {
        val (config, accounts) = setupDb("l4")
        setup(config)
        seed(config, accounts)

        val options = client.get("/users/filters").decode<UserFilterOptionsResponse>()
        assertEquals(listOf("en", "tr"), options.languages)
        assertEquals(listOf("1.0.0", "2.0.0"), options.appVersions)
        assertEquals(listOf("dizibook", "prayapp"), options.appNames)
    }

    @Test
    fun `me ucu token sahibinin hesabini ve oturumunu doner`() = testApplication {
        val (config, accounts) = setupDb("l5")
        setup(config)
        val ali = seed(config, accounts)[0]

        val me = client.get("/me") { header("X-Account", ali.toString()) }.decode<UserListItem>()
        assertEquals(ali, me.accountId)
        assertEquals("ali@ornek.com", me.email)
        assertEquals("cihaz-ali", me.deviceId)
        assertEquals("tr", me.language)

        val anonim = client.get("/me?deviceId=cihaz-anonim").decode<UserListItem>()
        assertNull(anonim.accountId)
        assertEquals(HttpStatusCode.NotFound, client.get("/me?deviceId=yok").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/me").status)
    }

    @Test
    fun `hesap tablosu verilmezse liste yine calisir`() = testApplication {
        val (config, _) = setupDb("l6", withAccounts = false)
        setup(config)
        session("""{"deviceId":"sadece-cihaz","language":"tr"}""")

        val list = client.get("/users").decode<PaginatedUsersResponse>()
        assertEquals(1, list.totalItems)
        assertNull(list.data.single().email, "hesap kaynagi yoksa email null doner")

        // Email ile arama yapilamaz; deviceId aramasi calismaya devam eder.
        assertEquals(1, client.get("/users?q=sadece").decode<PaginatedUsersResponse>().totalItems)
        assertEquals(0, client.get("/users?q=@ornek").decode<PaginatedUsersResponse>().totalItems)
    }

    @Test
    fun `sayfa boyutu sinirlanir`() = testApplication {
        val (config, accounts) = setupDb("l7")
        setup(config)
        seed(config, accounts)

        val response = client.get("/users?size=5000&page=0").decode<PaginatedUsersResponse>()
        assertEquals(100, response.size, "maxPageSize")
        assertEquals(1, response.page)
    }
}
