package com.furkan.userme

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ColumnSet
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.LocalDateTime

/** Liste filtreleri. Bos/null olanlar yok sayilir. */
data class UserQuery(
    /** Serbest arama: kullanici id'si (sayiysa), email, deviceId ya da sehir. */
    val q: String? = null,
    val language: String? = null,
    val appVersion: String? = null,
    /** Hangi uygulama: sessions.app_name. */
    val appName: String? = null,
    /** true: sadece hesabi olanlar, false: sadece anonim cihazlar, null: hepsi. */
    val registered: Boolean? = null,
    /** Son N gun icinde goruldu (lastSeenAt). */
    val days: Int? = null,
    /** Projeye ozel kolon filtreleri: "isPremium" -> "true" (bkz. [ExtraSource]). */
    val extras: Map<String, String> = emptyMap()
)

internal class UserListRepository(
    private val database: Database,
    private val table: SessionTable,
    private val accounts: AccountsSource?,
    private val extras: List<ExtraSource> = emptyList()
) {

    private val json = Json { ignoreUnknownKeys = true }

    /** Ek kolonlara adiyla erisim: "isPremium" -> DeviceFlags.isPremium */
    private val extraColumns: Map<String, Column<*>> =
        extras.flatMap { it.columns.entries }.associate { it.key to it.value }

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

    /** Tek bir oturum satiri; yazma sonrasi tazelenmis hali icin. */
    fun findBySession(sessionId: Int): UserListItem? = transaction(database) {
        joined().selectAll().where { table.id eq sessionId }.limit(1).firstOrNull()?.toItem()
    }

    /**
     * Okuma anindaki tazeleme; bir sey yazildiysa true doner.
     *
     * Sira onemli: acilis sayisina [newOpenAfter] icin `lastSeenAt` guncellenmeden ONCE bakilir.
     * [profile] doluysa (istemci yeni surum/dil gonderdi) hemen yazilir; yoksa `lastSeenAt`
     * sadece kayit [minWriteInterval] suresinden eskiyse guncellenir.
     */
    fun touchOnRead(
        sessionId: Int,
        profile: SessionRequest?,
        minWriteInterval: java.time.Duration,
        newOpenAfter: java.time.Duration?
    ): Boolean = transaction(database) {
        val now = LocalDateTime.now()

        val yeniAcilis = newOpenAfter?.let { sure ->
            table.update({ (table.id eq sessionId) and (table.lastSeenAt lessEq now.minus(sure)) }) {
                with(SqlExpressionBuilder) { it[table.openCount] = table.openCount + 1 }
            } > 0
        } ?: false

        val yazilan = if (profile != null) {
            table.update({ table.id eq sessionId }) {
                profile.platform?.let { v -> it[table.platform] = v.take(50) }
                profile.appVersion?.let { v -> it[table.appVersion] = v.take(50) }
                profile.appName?.let { v -> it[table.appName] = v.take(100) }
                profile.language?.let { v -> it[table.language] = v.take(10) }
                profile.city?.let { v -> it[table.city] = v.take(255) }
                profile.latitude?.let { v -> it[table.latitude] = v }
                profile.longitude?.let { v -> it[table.longitude] = v }
                it[table.lastSeenAt] = now
            }
        } else {
            table.update({ (table.id eq sessionId) and (table.lastSeenAt lessEq now.minus(minWriteInterval)) }) {
                it[table.lastSeenAt] = now
            }
        }
        yeniAcilis || yazilan > 0
    }

    /** Oturum tablosu + (varsa) hesap tablosu + projeye ozel ek tablolar, hepsi LEFT JOIN. */
    private fun joined(): ColumnSet {
        var source: ColumnSet = table
        accounts?.let { source = source.join(it.table, JoinType.LEFT, table.accountId, it.id) }
        extras.forEach { extra ->
            val sessionColumn = when (extra.joinKey) {
                SessionJoinKey.DEVICE_ID -> table.deviceId
                SessionJoinKey.ACCOUNT_ID -> table.accountId
            }
            source = source.join(extra.table, JoinType.LEFT, sessionColumn, extra.foreignKey)
        }
        return source
    }

    private fun buildCondition(f: UserQuery): (SqlExpressionBuilder.() -> Op<Boolean>)? {
        val parts = mutableListOf<SqlExpressionBuilder.() -> Op<Boolean>>()

        f.q?.trim()?.takeIf { it.isNotBlank() }?.let { raw ->
            val like = "%${raw.lowercase()}%"
            val id = raw.toIntOrNull()
            parts += {
                var op: Op<Boolean> = (table.deviceId.lowerCase() like like) or
                    (table.city.lowerCase() like like)
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
        f.days?.takeIf { it > 0 }?.let { gun ->
            val since = LocalDateTime.now().minusDays(gun.toLong())
            parts += { table.lastSeenAt greaterEq since }
        }
        when (f.registered) {
            true -> parts += { table.accountId.isNotNull() }
            false -> parts += { table.accountId.isNull() }
            null -> Unit
        }

        // Projeye ozel kolon filtreleri: ?isPremium=true gibi.
        f.extras.forEach { (name, raw) ->
            val column = extraColumns[name] ?: return@forEach
            val value = raw.trim().takeIf { it.isNotBlank() } ?: return@forEach
            parts += { column.matches(value) }
        }

        if (parts.isEmpty()) return null
        return { parts.map { it() }.reduce { acc, op -> acc and op } }
    }

    /** Kolonun tipine gore esitlik karsilastirmasi; cevirilemeyen deger eslesmez. */
    @Suppress("UNCHECKED_CAST")
    private fun Column<*>.matches(raw: String): Op<Boolean> = SqlExpressionBuilder.run {
        when {
            this@matches.columnType.javaClass.simpleName.contains("Boolean", ignoreCase = true) ->
                raw.toBooleanStrictOrNull()?.let { (this@matches as Column<Boolean>) eq it } ?: Op.FALSE
            this@matches.columnType.javaClass.simpleName.contains("Integer", ignoreCase = true) ->
                raw.toIntOrNull()?.let { (this@matches as Column<Int>) eq it } ?: Op.FALSE
            this@matches.columnType.javaClass.simpleName.contains("Long", ignoreCase = true) ->
                raw.toLongOrNull()?.let { (this@matches as Column<Long>) eq it } ?: Op.FALSE
            else -> (this@matches as Column<String>) eq raw
        }
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
            extras = extras(),
            openCount = this[table.openCount],
            firstSeenAt = this[table.firstSeenAt].toString(),
            lastSeenAt = this[table.lastSeenAt].toString()
        )
    }

    /** Projeye ozel kolonlari JSON nesnesine cevirir; satir yoksa alanlar null doner. */
    private fun ResultRow.extras(): JsonObject {
        if (extraColumns.isEmpty()) return JsonObject(emptyMap())
        return JsonObject(extraColumns.mapValues { (_, column) -> toJson(getOrNull(column)) })
    }

    private fun toJson(value: Any?) = when (value) {
        null -> JsonNull
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is EntityID<*> -> JsonPrimitive(value.value.toString())
        else -> JsonPrimitive(value.toString())
    }

    private fun ResultRow.metadata(): JsonObject =
        runCatching { json.parseToJsonElement(this[table.metadata]) as? JsonObject }.getOrNull()
            ?: JsonObject(emptyMap())
}
