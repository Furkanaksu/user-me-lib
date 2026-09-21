package com.furkan.userme

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.UpdateBuilder
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.LocalDateTime

/** Istekten gelen oturum alanlari. null alan = mevcut deger korunur. */
internal data class SessionData(
    val platform: String?,
    val appVersion: String?,
    val appName: String?,
    val language: String?,
    val city: String?,
    val latitude: Double?,
    val longitude: Double?,
    val metadata: JsonObject?
)

/**
 * Tum sorgular `transaction(database)` ile, disaridan verilen DB uzerinde calisir.
 * Kutuphane global transaction'a ya da kendi baglantisina asla guvenmez.
 */
internal class SessionRepository(
    private val database: Database,
    private val table: SessionTable
) {

    private val json = Json { ignoreUnknownKeys = true }

    /** Giris yok: oturum deviceId'ye aittir. */
    fun upsertAnonymous(deviceId: String, data: SessionData): SessionResponse = transaction(database) {
        val now = LocalDateTime.now()
        val existing = table.selectAll()
            .where { (table.deviceId eq deviceId) and table.accountId.isNull() }
            .forUpdate()
            .firstOrNull()

        val id = if (existing == null) {
            table.insert {
                it[this.deviceId] = deviceId
                it[this.accountId] = null
                it.writeData(data, previous = null)
                it[this.openCount] = 1
                it[this.firstSeenAt] = now
                it[this.lastSeenAt] = now
            }[table.id].value
        } else {
            val existingId = existing[table.id].value
            table.update({ table.id eq existingId }) {
                it.writeData(data, previous = existing)
                it[this.openCount] = existing[table.openCount] + 1
                it[this.lastSeenAt] = now
            }
            existingId
        }
        findById(id)!!
    }

    /**
     * Giris var: oturum hesaba aittir (hesap basina tek satir), deviceId son kullanilan cihaz olur.
     * Cihazin o ana kadarki anonim oturumu hesaba devredilir:
     * - hesabin henuz oturumu yoksa anonim satir hesaba baglanir (gecmis korunur),
     * - varsa anonim satirin acilis sayisi ve ilk gorulme tarihi hesaba eklenir, anonim satir silinir.
     */
    fun upsertForAccount(accountId: Int, deviceId: String, data: SessionData): SessionResponse =
        transaction(database) {
            val now = LocalDateTime.now()

            val accountSession = table.selectAll()
                .where { table.accountId eq accountId }
                .forUpdate()
                .firstOrNull()
            val anonymous = table.selectAll()
                .where { (table.deviceId eq deviceId) and table.accountId.isNull() }
                .forUpdate()
                .firstOrNull()

            val id = when {
                accountSession != null -> {
                    val sessionId = accountSession[table.id].value
                    val firstSeen = listOfNotNull(
                        accountSession[table.firstSeenAt],
                        anonymous?.get(table.firstSeenAt)
                    ).min()
                    val extraOpens = anonymous?.get(table.openCount) ?: 0
                    val base = anonymous?.let { mergeMetadata(it.metadata(), accountSession.metadata()) }

                    table.update({ table.id eq sessionId }) {
                        it[this.deviceId] = deviceId
                        it.writeData(data, previous = accountSession, baseMetadata = base)
                        it[this.openCount] = accountSession[table.openCount] + extraOpens + 1
                        it[this.firstSeenAt] = firstSeen
                        it[this.lastSeenAt] = now
                    }
                    if (anonymous != null) {
                        val anonId = anonymous[table.id].value
                        table.deleteWhere { b -> b.run { table.id eq anonId } }
                    }
                    sessionId
                }

                anonymous != null -> {
                    val anonId = anonymous[table.id].value
                    table.update({ table.id eq anonId }) {
                        it[this.accountId] = accountId
                        it.writeData(data, previous = anonymous)
                        it[this.openCount] = anonymous[table.openCount] + 1
                        it[this.lastSeenAt] = now
                    }
                    anonId
                }

                else -> table.insert {
                    it[this.deviceId] = deviceId
                    it[this.accountId] = accountId
                    it.writeData(data, previous = null)
                    it[this.openCount] = 1
                    it[this.firstSeenAt] = now
                    it[this.lastSeenAt] = now
                }[table.id].value
            }
            findById(id)!!
        }

    /** Cihazin anonim oturumu. */
    fun findByDevice(deviceId: String): SessionResponse? = transaction(database) {
        table.selectAll()
            .where { (table.deviceId eq deviceId) and table.accountId.isNull() }
            .limit(1)
            .firstOrNull()
            ?.toResponse()
    }

    fun findByAccount(accountId: Int): SessionResponse? = transaction(database) {
        table.selectAll()
            .where { table.accountId eq accountId }
            .limit(1)
            .firstOrNull()
            ?.toResponse()
    }

    private fun findById(id: Int): SessionResponse? =
        table.selectAll().where { table.id eq id }.limit(1).firstOrNull()?.toResponse()

    /** null gelen alanlar mevcut degeri korur; metadata anahtar bazinda birlestirilir. */
    private fun UpdateBuilder<*>.writeData(
        data: SessionData,
        previous: ResultRow?,
        baseMetadata: JsonObject? = null
    ) {
        fun <T> pick(new: T?, column: Column<T?>): T? = new ?: previous?.get(column)

        this[table.platform] = pick(data.platform, table.platform)
        this[table.appVersion] = pick(data.appVersion, table.appVersion)
        this[table.appName] = pick(data.appName, table.appName)
        this[table.language] = pick(data.language, table.language)
        this[table.city] = pick(data.city, table.city)
        this[table.latitude] = pick(data.latitude, table.latitude)
        this[table.longitude] = pick(data.longitude, table.longitude)

        val current = baseMetadata ?: previous?.metadata() ?: JsonObject(emptyMap())
        this[table.metadata] = mergeMetadata(current, data.metadata ?: JsonObject(emptyMap())).toString()
    }

    private fun mergeMetadata(current: JsonObject, incoming: JsonObject) = JsonObject(current + incoming)

    private fun ResultRow.metadata(): JsonObject =
        runCatching { json.parseToJsonElement(this[table.metadata]) as? JsonObject }.getOrNull()
            ?: JsonObject(emptyMap())

    private fun ResultRow.toResponse() = SessionResponse(
        id = this[table.id].value,
        deviceId = this[table.deviceId],
        accountId = this[table.accountId],
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
