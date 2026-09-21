package com.furkan.userme

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Uygulama oturumlari.
 * - Giris yoksa: satir deviceId'ye aittir, accountId null.
 * - Giris varsa: satir hesaba aittir (hesap basina tek satir), deviceId son kullanilan cihazdir.
 *
 * `account_id` bilerek duz bir tamsayidir, foreign key degildir: hesap tablosu baska bir
 * kutuphanede (auth-lib) ya da hic yok olabilir.
 */
class SessionTable(tableName: String) : IntIdTable(tableName) {
    val deviceId = varchar("device_id", 255).index()
    val accountId = integer("account_id").nullable().uniqueIndex()
    val platform = varchar("platform", 50).nullable()
    val appVersion = varchar("app_version", 50).nullable()
    val appName = varchar("app_name", 100).nullable()
    val language = varchar("language", 10).nullable()
    val city = varchar("city", 255).nullable()
    val latitude = double("latitude").nullable()
    val longitude = double("longitude").nullable()

    /** Uygulamaya ozel alanlar (tema, izinler...) JSON nesnesi olarak. */
    val metadata = text("metadata").default("{}")
    val openCount = integer("open_count").default(1)
    val firstSeenAt = datetime("first_seen_at")
    val lastSeenAt = datetime("last_seen_at").index()
}

/**
 * Tabloyu, config'te verilen DB'de olusturur/gunceller.
 * Kutuphane kendiliginden calistirmaz; proje acilista bir kez cagirir.
 */
fun UserMeConfig.migrate() = transaction(database) {
    @Suppress("DEPRECATION")
    SchemaUtils.createMissingTablesAndColumns(sessions)
}
