package com.furkan.userme

import io.ktor.server.application.ApplicationCall
import org.jetbrains.exposed.sql.Database

/**
 * Kullanicinin kendine dair bilgileri: cihaz/oturum kaydi ve "benim bilgilerim" ucu.
 *
 * Giris (login/register/token) bu kutuphanenin isi DEGILDIR; o auth-lib'de. Buraya sadece
 * "su anki istegin sahibi kim" bilgisi [accountId] ile disaridan verilir:
 *
 * ```
 * UserMeConfig(
 *     database = db,
 *     tablePrefix = "prayapp_",
 *     accountId = { call -> call.currentAccount()?.accountId }   // auth-lib kullaniliyorsa
 * )
 * ```
 *
 * Girisi olmayan projeler [accountId]'yi hic vermez; tum oturumlar cihaza baglanir.
 *
 * @param database    Projenin kendi Exposed [Database] objesi. Kutuphane asla connect() cagirmaz.
 * @param basePath    Route'larin monte edilecegi taban yol.
 * @param tablePrefix Tablo adinin onune eklenir: "prayapp_" -> prayapp_sessions.
 * @param accountId   Istegin sahibi olan hesabin id'si; null donerse oturum cihaza baglanir.
 */
data class UserMeConfig(
    val database: Database,
    val basePath: String = "/me",
    val tablePrefix: String = "",
    val accountId: (ApplicationCall) -> Int? = { null }
) {
    val sessions: SessionTable by lazy { SessionTable("${tablePrefix}sessions") }

    init {
        require(basePath.startsWith("/")) { "basePath '/' ile baslamali: $basePath" }
    }
}
