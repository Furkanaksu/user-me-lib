package com.furkan.userme

import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Table

/**
 * Hesap tablosuna disaridan verilen erisim.
 *
 * Bu kutuphane hesaplarin nerede tutuldugunu bilmez (auth-lib olabilir, baska bir sey olabilir).
 * Tablo ve kolonlar parametre olarak verilince email ile arama ve email/ad gosterimi
 * SQL join'i ile calisir; hafizada filtreleme yapilmaz.
 *
 * auth-lib ile:
 * ```
 * AccountsSource(
 *     table = auth.accounts,
 *     id = auth.accounts.id,
 *     email = auth.accounts.email,
 *     displayName = auth.accounts.displayName
 * )
 * ```
 *
 * Verilmezse liste yine calisir; email ve ad alanlari null doner, email ile arama yapilmaz.
 */
data class AccountsSource(
    val table: Table,
    val id: Column<EntityID<Int>>,
    val email: Column<String?>,
    val displayName: Column<String?>? = null
)
