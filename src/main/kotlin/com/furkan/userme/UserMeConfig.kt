package com.furkan.userme

import io.ktor.server.application.ApplicationCall
import org.jetbrains.exposed.sql.Database
import kotlin.time.Duration

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
 * @param extras      Projeye ozel ek tablolar; kolonlari cevapta `extras` altinda doner ve
 *                    ayni isimle filtrelenebilir (bkz. [ExtraSource]).
 * @param readTracking Dolu ise `GET {basePath}` istegi ayni zamanda "uygulama acildi" sinyali
 *                    sayilir (bkz. [ReadTracking]). null: okuma hicbir sey yazmaz.
 */
data class UserMeConfig(
    val database: Database,
    val basePath: String = "/me",
    val usersPath: String = "/users",
    val tablePrefix: String = "",
    val accountId: (ApplicationCall) -> Int? = { null },
    val accounts: AccountsSource? = null,
    val extras: List<ExtraSource> = emptyList(),
    val readTracking: ReadTracking? = null,
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

/**
 * `GET {basePath}` okundugunda kaydin tazelenmesi.
 *
 * Giris (`/auth/device` benzeri) sadece token yokken yapilan bir istektir; uygulamanin her
 * acilista attigi istek bu uctur. Bu yuzden okuma ayni zamanda bir acilis sinyalidir:
 * "son gorulme" tazelenir, yeterince sessiz gecmisse acilis sayilir ve istemcinin sorguda
 * gonderdigi profil alanlari (`appVersion`, `language`, `platform`, `appName`, `city`,
 * `latitude`, `longitude`) guncellenir.
 *
 * @param minWriteInterval Kayit bu sureden yeniyse `lastSeenAt` tekrar yazilmaz. Profil
 *        degistiginde bu sinir beklenmez, hemen yazilir.
 * @param newOpenAfter Kayit bu sureden beri sessizse okuma yeni bir acilis sayilir ve
 *        `openCount` 1 artar. null: acilis sayilmaz.
 */
data class ReadTracking(
    val minWriteInterval: Duration = Duration.parse("5m"),
    val newOpenAfter: Duration? = null
)
