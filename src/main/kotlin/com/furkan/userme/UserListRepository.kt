package com.furkan.userme

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ColumnSet
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/** Liste filtreleri. Bos/nulll olanlar yok sayilir. */
data class UserQuery(
    /** Serbest arama: kullanici id'si (sayiysa), email ya da deviceId. */
    val q: String? = null,
    val language: String? = null,
    val appVersion: String? = null,
    /** Hangi uygulama: sessions.app_name. */
    val appName: String? = null,
    /** true: sadece hesabi olanlar, false: sadece anonim cihazlar, null: hepsi. */
    val registered: Boolean? = null
)

internal class UserListRepository(
    private val database: Database,
    private val table: SessionTable,
    private val accounts: AccountsSource?
) {

    private val json = Json { ignoreUnknownKeys = true }

    fun query(filter: UserQuery, page: Int, size: Int): Pair<List<UserListItem>, Long> =
        transaction(database) {
            val source = joined()
            val condition = buildCondition(filter)
            val base = { if (condition != null) source.selectAll().where(condition) else source.selectAll() }

            val total = base().count()
            val items = base()
                .orderBy(table.lastSeenAt, SortOrder.DESC)
                .limit(size).offset(((page - 1).coerceAtLeast(0).toLong()) * size)
                .map { it.toItem() }
            items to total
        }

    /** Dropdown'lari doldurmak icin kullanilan degerler. */
    fun filterOptions(): UserFilterOptionsResponse = transaction(database) {
        fun distinct(column: Column<String?>): List<String> =
            table.select(column).withDistinct()
                .mapNotNull { it[column]?.takeIf(String::isNotBlank) }
                .sorted()

        UserFilterOptionsResponse(
            languages = distinct(table.language),
            appVersions = distinct(table.appVersion),
            appNames = distinct(table.appName)
        )
    }

    /** Token sahibinin bilgileri: hesap + oturum. */
    fun findByAccount(accountId: Int): UserListItem? = transaction(database) {
        joined().selectAll().where { table.accountId eq accountId }.limit(1).firstOrNull()?.toItem()
    }

    fun findByDevice(deviceId: String): UserListItem? = transaction(database) {
        joined().selectAll()
            .where { (table.deviceId eq deviceId) and table.accountId.isNull() }
            .limit(1)
            .firstOrNull()
            ?.toItem()
    }

    /** Hesap tablosu verilmisse LEFT JOIN, verilmemisse sadece oturumlar. */
    private fun joined(): ColumnSet =
        accounts?.let { table.join(it.table, JoinType.LEFT, table.accountId, it.id) } ?: table

    private fun buildCondition(f: UserQuery): (SqlExpressionBuilder.() -> Op<Boolean>)? {
        val parts = mutableListOf<SqlExpressionBuilder.() -> Op<Boolean>>()

        f.q?.trim()?.takeIf { it.isNotBlank() }?.let { raw ->
            val like = "%${raw.lowercase()}%"
            val id = raw.toIntOrNull()
            parts += {
                var op: Op<Boolean> = table.deviceId.lowerCase() like like
                accounts?.let { op = op or (it.email.lowerCase() like like) }
                if (id != null) {
                    op = op or (table.accountId eq id) or (table.id eq id)
                }
                op
            }
        }
        f.language?.takeIf(String::isNotBlank)?.let { v -> parts += { table.language eq v } }
        f.appVersion?.takeIf(String::isNotBlank)?.let { v -> parts += { table.appVersion eq v } }
        f.appName?.takeIf(String::isNotBlank)?.let { v -> parts += { table.appName eq v } }
        when (f.registered) {
            true -> parts += { table.accountId.isNotNull() }
            false -> parts += { table.accountId.isNull() }
            null -> Unit
        }

        if (parts.isEmpty()) return null
        return { parts.map { it() }.reduce { acc, op -> acc and op } }
    }

    private fun ResultRow.toItem(): UserListItem {
        val accountId = this[table.accountId]
        return UserListItem(
            sessionId = this[table.id].value,
            accountId = accountId,
            email = accounts?.let { src -> if (accountId != null) this.getOrNull(src.email) else null },
            displayName = accounts?.displayName?.let { col -> if (accountId != null) this.getOrNull(col) else null },
            deviceId = this[table.deviceId],
            platform = this[table.platform],
            appVersion = this[table.appVersion],
            appName = this[table.appName],
            language = this[table.language],
            city = this[table.city],
            latitude = this[table.latitude],
            longitude = this[table.longitude],
            metadata = metadata(),
            openCount = this[table.openCount],
            firstSeenAt = this[table.firstSeenAt].toString(),
            lastSeenAt = this[table.lastSeenAt].toString()
        )
    }

    private fun ResultRow.metadata(): JsonObject =
        runCatching { json.parseToJsonElement(this[table.metadata]) as? JsonObject }.getOrNull()
            ?: JsonObject(emptyMap())
}
