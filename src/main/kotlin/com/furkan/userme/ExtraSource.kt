package com.furkan.userme

import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Table

/** Ek tablonun oturuma hangi kolondan baglandigi. */
enum class SessionJoinKey {
    /** Ek tablo cihaz basina: sessions.device_id ile eslesir. */
    DEVICE_ID,

    /** Ek tablo hesap basina: sessions.account_id ile eslesir. */
    ACCOUNT_ID
}

/**
 * Oturum listesine PROJEYE OZEL kolonlar eklemek icin.
 *
 * Kutuphane premium, bildirim token'i gibi alanlarin varligini bilmez; proje kendi tablosunu
 * ve gostermek istedigi kolonlari verir. Kolonlar cevapta `extras` altinda doner ve ayni
 * isimle sorgu parametresi olarak filtrelenebilir.
 *
 * ```
 * ExtraSource(
 *     table = DeviceFlags,
 *     foreignKey = DeviceFlags.deviceId,
 *     joinKey = SessionJoinKey.DEVICE_ID,
 *     columns = mapOf(
 *         "isPremium" to DeviceFlags.isPremium,
 *         "premiumExpiryDate" to DeviceFlags.premiumExpiryDate
 *     )
 * )
 * ```
 *
 * Sonuc: `GET {usersPath}?isPremium=true` calisir ve her satirda
 * `"extras": { "isPremium": true, "premiumExpiryDate": "2027-01-01" }` doner.
 *
 * @param table Eklenecek tablo.
 * @param foreignKey Bu tablodaki join kolonu.
 * @param joinKey Oturum tablosundaki karsiligi.
 * @param columns Cevapta ve filtrede kullanilacak alanlar: gorunen ad -> kolon.
 */
data class ExtraSource(
    val table: Table,
    val foreignKey: Column<*>,
    val joinKey: SessionJoinKey,
    val columns: Map<String, Column<*>>
) {
    init {
        require(columns.isNotEmpty()) { "ExtraSource en az bir kolon icermeli" }
        require(columns.keys.none { it.isBlank() }) { "Kolon adi bos olamaz" }
    }
}
