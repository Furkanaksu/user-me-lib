package com.furkan.userme

import io.ktor.server.application.ApplicationCall
import org.jetbrains.exposed.sql.Database

/**
 * Kullanicinin kendine dair bilgileri: cihaz/oturum kaydi, "benim bilgilerim" ucu ve
 * kayitli kullanicilarin listesi.
 *
 * Giris (login/register/token) bu kutuphanenin isi DEGILDIR; o auth-lib'de. Buraya sadece
 * "su anki istegin sahibi kim" bilgisi [accountId] ile disaridan verilir:
 *
 * ```
 * UserMeConfig(
 *     database = db,
 *     tablePrefix = "prayapp_",
 *     accountId = { call -> call.currentAccount()?.accountId },  // auth-lib kullaniliyorsa
 *     accounts = AccountsSource(auth.accounts, auth.accounts.id, auth.accounts.email)
 * )
 * ```
 *
 * Girisi olmayan projeler [accountId] ve [accounts] vermez; tum oturumlar cihaza baglanir.
 *
 * @param database    Projenin kendi Exposed [Database] objesi. Kutuphane asla connect() cagirmaz.
 * @param basePath    "Benim bilgilerim" uclarinin taban yolu.
 * @param usersPath   Kullanici listesinin yolu ([userListRoutes] ile monte edilir).
 * @param tablePrefix Tablo adinin onune eklenir: "prayapp_" -> prayapp_sessions.
 * @param accountId   Istegin sahibi olan hesabin id'si; null donerse oturum cihaza baglanir.
 * @param accounts    Hesap tablosu erisimi; verilmezse email alanlari null doner.
 */
data class UserMeConfig(
    val database: Database,
    val basePath: String = "/me",
    val usersPath: String = "/users",
    val tablePrefix: String = "",
    val accountId: (ApplicationCall) -> Int? = { null },
    val accounts: AccountsSource? = null,
    val defaultPageSize: Int = 25,
    val maxPageSize: Int = 100
) {
    val sessions: SessionTable by lazy { SessionTable("${tablePrefix}sessions") }

    init {
        require(basePath.startsWith("/")) { "basePath '/' ile baslamali: $basePath" }
        require(usersPath.startsWith("/")) { "usersPath '/' ile baslamali: $usersPath" }
        require(defaultPageSize in 1..maxPageSize) { "defaultPageSize 1..maxPageSize araliginda olmali" }
    }
}
