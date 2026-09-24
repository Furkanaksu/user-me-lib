@file:OptIn(ExperimentalSerializationApi::class)

package com.furkan.userme

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonObject

/**
 * Uygulama acilisinda gonderilen oturum bilgisi. Sadece deviceId zorunlu.
 * Mevcut istemcilerle uyum icin app_version / app_name adlari da kabul edilir.
 */
@Serializable
data class SessionRequest(
    val deviceId: String? = null,
    val platform: String? = null,
    @JsonNames("app_version")
    val appVersion: String? = null,
    @JsonNames("app_name")
    val appName: String? = null,
    val language: String? = null,
    val city: String? = null,
    @Serializable(with = LenientDoubleSerializer::class)
    val latitude: Double? = null,
    @Serializable(with = LenientDoubleSerializer::class)
    val longitude: Double? = null,
    /** Uygulamaya ozel alanlar. Var olan metadata ile birlestirilir (ayni anahtar ustune yazilir). */
    val metadata: JsonObject? = null
)

@Serializable
data class SessionResponse(
    val id: Int,
    val deviceId: String,
    /** null: anonim cihaz oturumu. Dolu: oturum bu hesaba ait. */
    val accountId: Int?,
    val platform: String?,
    val appVersion: String?,
    val appName: String?,
    val language: String?,
    val city: String?,
    val latitude: Double?,
    val longitude: Double?,
    val metadata: JsonObject,
    val openCount: Int,
    val firstSeenAt: String,
    val lastSeenAt: String
)

/**
 * Listede ve "benim bilgilerim" ucunda donen kullanici: oturum + (varsa) hesap bilgisi.
 * Hesabi olmayan kayitlar anonim cihazlardir: accountId, email ve displayName null gelir.
 */
@Serializable
data class UserListItem(
    val sessionId: Int,
    val accountId: Int?,
    val email: String?,
    val displayName: String?,
    val deviceId: String,
    val platform: String?,
    val appVersion: String?,
    val appName: String?,
    val language: String?,
    val city: String?,
    val latitude: Double?,
    val longitude: Double?,
    val metadata: JsonObject,
    /** Projeye ozel alanlar (premium, bildirim token'i...). Bkz. [ExtraSource]. */
    val extras: JsonObject = JsonObject(emptyMap()),
    val openCount: Int,
    val firstSeenAt: String,
    val lastSeenAt: String
)

@Serializable
data class PaginatedUsersResponse(
    val data: List<UserListItem>,
    val page: Int,
    val size: Int,
    val totalItems: Long,
    val totalPages: Int
)

@Serializable
data class UserFilterOptionsResponse(
    val languages: List<String>,
    val appVersions: List<String>,
    val appNames: List<String>
)

@Serializable
data class UserMeErrorResponse(
    val status: String = "fail",
    val error: String
)
