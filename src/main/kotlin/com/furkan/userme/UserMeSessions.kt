package com.furkan.userme

/**
 * Oturum mantigina HTTP olmadan, kod uzerinden erisim.
 *
 * Projeler kendi uclarini ve cevap sekillerini koruyup altta bu mantigi kullanabilir:
 *
 * ```
 * val sessions = UserMeSessions(config)
 * val session = sessions.upsert(SessionRequest(deviceId = "abc", platform = "android"))
 * ```
 *
 * Okuma tarafinda [UserMeConfig.sessions] tablosu uzerinde kendi Exposed sorgularini
 * yazabilirsin (admin listesi, istatistik vb. bu kutuphanenin isi degil).
 */
class UserMeSessions(config: UserMeConfig) {

    private val repository = SessionRepository(config.database, config.sessions)

    /**
     * Oturumu olusturur ya da gunceller.
     *
     * @param request Oturum alanlari. `deviceId` zorunlu; verilmeyen alanlar eski degerini korur.
     * @param accountId Giris yapilmissa hesabin id'si. null ise oturum cihaza baglanir;
     *        doluysa cihazin anonim gecmisi hesaba devredilir.
     */
    fun upsert(request: SessionRequest, accountId: Int? = null): SessionResponse {
        val deviceId = request.deviceId?.trim()
        require(!deviceId.isNullOrBlank()) { "deviceId bos olamaz" }
        require(deviceId.length <= 255) { "deviceId en fazla 255 karakter olabilir" }

        val data = SessionData(
            platform = request.platform?.take(50),
            appVersion = request.appVersion?.take(50),
            appName = request.appName?.take(100),
            language = request.language?.take(10),
            city = request.city?.take(255),
            latitude = request.latitude,
            longitude = request.longitude,
            metadata = request.metadata
        )

        return if (accountId != null) {
            repository.upsertForAccount(accountId, deviceId, data)
        } else {
            repository.upsertAnonymous(deviceId, data)
        }
    }

    /** Cihazin anonim oturumu. */
    fun findByDevice(deviceId: String): SessionResponse? = repository.findByDevice(deviceId)

    fun findByAccount(accountId: Int): SessionResponse? = repository.findByAccount(accountId)
}
