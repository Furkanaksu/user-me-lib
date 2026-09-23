package com.furkan.userme

/**
 * Kullanici listesine HTTP olmadan, kod uzerinden erisim.
 *
 * Projeler kendi uclarini ve cevap sekillerini koruyup altta bu sorguyu kullanabilir:
 *
 * ```
 * val users = UserMeUsers(config)
 * val (rows, total) = users.query(UserQuery(q = "ali@", language = "tr"), page = 1, size = 25)
 * ```
 */
class UserMeUsers(config: UserMeConfig) {

    private val repository = UserListRepository(config.database, config.sessions, config.accounts)

    /** Sayfali liste. Arama [UserQuery.q] ile: kullanici id'si, email ya da deviceId. */
    fun query(filter: UserQuery = UserQuery(), page: Int = 1, size: Int = 25): Pair<List<UserListItem>, Long> =
        repository.query(filter, page.coerceAtLeast(1), size.coerceAtLeast(1))

    /** Dropdown'lar icin kullanilan dil / surum / uygulama degerleri. */
    fun filterOptions(): UserFilterOptionsResponse = repository.filterOptions()

    /** Token sahibinin bilgileri (hesap + oturum). */
    fun findByAccount(accountId: Int): UserListItem? = repository.findByAccount(accountId)

    /** Giris yapmamis bir cihazin bilgileri. */
    fun findByDevice(deviceId: String): UserListItem? = repository.findByDevice(deviceId)
}
