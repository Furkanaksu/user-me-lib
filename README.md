# user-me-lib

Ktor + Exposed tabanlı, **kullanıcı kütüphanesi**: uygulama açılışındaki oturum/cihaz kaydı,
"benim bilgilerim" ucu ve kayıtlı kullanıcıların listesi.

- Giriş yoksa oturum `deviceId`'ye bağlanır.
- Giriş varsa oturum **hesaba** bağlanır; cihazın o ana kadarki anonim geçmişi hesaba devredilir.
- Kayıt / giriş / token işleri burada **yoktur**, onlar [auth-lib](https://github.com/Furkanaksu/auth-lib)'de.
- Kullanıcı listesi burada; ama korumasını (admin auth) ve uygulamaya özel alanları (premium, izinler) proje yönetir.

Kütüphane auth-lib'e bağımlı değildir: giriş yapılmışsa hesabın id'sini tüketen proje tek
satırlık bir lambda ile verir. Girişi olmayan projeler bunu hiç vermez.

Altyapı diğer kütüphanelerle aynıdır: JDK 21, Kotlin 2.2.21, Ktor 3.3.2, Exposed 0.61.0.

## Kurulum

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://jitpack.io")
    }
}

// build.gradle.kts
implementation("com.github.Furkanaksu:user-me-lib:2.0.0")
```

## Kullanım

```kotlin
fun Application.module() {
    val db = Database.connect(/* projenin kendi DB'si */)
    install(ContentNegotiation) { json() }

    val me = UserMeConfig(
        database = db,
        tablePrefix = "prayapp_",                              // prayapp_sessions
        accountId = { call -> call.currentAccount()?.accountId } // auth-lib kullaniliyorsa
    )
    me.migrate()

    routing { userMeRoutes(me) }
}
```

Girişi olmayan bir proje `accountId`'yi hiç vermez:

```kotlin
val me = UserMeConfig(database = db)
```

## Uç noktalar

`basePath` varsayılanı `/me`:

| Metot | Yol | Açıklama | Monte eden |
| --- | --- | --- | --- |
| GET | `/me` | Giriş varsa hesabın bilgileri, yoksa `?deviceId=` ile cihazın bilgileri | `userMeRoutes` |
| POST | `/me/session` | Oturum kaydı | `sessionWriteRoutes` |
| GET | `/users` | Sayfalı kullanıcı listesi — `q`, `language`, `appVersion`, `appName`, `registered`, `page`, `size` | `userListRoutes` |
| GET | `/users/filters` | Dropdown değerleri: diller, sürümler, uygulamalar | `userListRoutes` |

`/users` herkesin email'ini döndürür, bu yüzden **ayrı monte edilir** ve koruması projeye bırakılır:

```kotlin
routing {
    userMeRoutes(me)                              // uygulama ucu
    authenticate("admin") { userListRoutes(me) }  // liste, senin kendi auth'unla
}
```

### `GET /me` — uygulamanın her açılışta attığı istek

Giriş (auth-lib'de `POST /auth/device` gibi) sadece elde geçerli token yokken yapılır: ilk
kurulumda ve refresh başarısız olduğunda. Uygulamanın her açılışta attığı istek budur.

`readTracking` verilirse aynı istek kaydı da tazeler:

```kotlin
UserMeConfig(
    database = db,
    readTracking = ReadTracking(
        minWriteInterval = 5.minutes,   // kayıt bundan yeniyse lastSeenAt tekrar yazılmaz
        newOpenAfter = 30.minutes       // bundan uzun sessizlik = yeni açılış, openCount + 1
    )
)
```

- Sorguda profil alanları gönderilebilir: `GET /me?appVersion=2.3.0&language=tr&platform=android`
  (ayrıca `appName`, `city`, `latitude`, `longitude`; `app_version` / `app_name` da kabul edilir).
- Değer değişmediyse `UPDATE` üretilmez; değiştiyse `minWriteInterval` beklenmeden hemen yazılır.
- `readTracking` verilmezse `GET /me` hiçbir şey yazmaz.

### Oturumu kim yazar

`POST /me/session` **ayrı monte edilir** (`sessionWriteRoutes`), çünkü her projeye gerekmez:

- **Girişi auth-lib ile yapan projeler** bu ucu hiç monte etmez. Oturum, giriş anında
  `UserMeSessions.upsert(...)` ile yazılır; sonraki açılışlarda `GET /me` günceller.
- **Kendi giriş akışı olmayan projeler** için hazır bir yazma ucudur.

Gövde — sadece `deviceId` zorunlu:

```json
{
  "deviceId": "a1b2c3",
  "platform": "android",
  "appVersion": "2.3.0",
  "appName": "prayapp",
  "language": "tr",
  "city": "Istanbul",
  "latitude": 41.0082,
  "longitude": 28.9784,
  "metadata": { "tema": "koyu" }
}
```

- Gönderilmeyen alan eski değerini korur; `metadata` anahtar bazında birleştirilir.
- Mevcut istemcilerle uyum: `app_version` / `app_name` adları ve tırnaklı koordinat (`"41.0082"`) da kabul edilir.

## Kullanıcı listesi

`GET /users` oturum tablosunu, varsa hesap tablosuyla birleştirip döner:

```json
{
  "data": [
    {
      "sessionId": 12, "accountId": 3, "email": "ali@ornek.com", "displayName": "Ali",
      "deviceId": "a1b2c3", "platform": "android", "appVersion": "2.3.0", "appName": "prayapp",
      "language": "tr", "city": "Istanbul", "openCount": 14,
      "firstSeenAt": "...", "lastSeenAt": "..."
    }
  ],
  "page": 1, "size": 25, "totalItems": 137, "totalPages": 6
}
```

| Parametre | Ne yapar |
| --- | --- |
| `q` | Arama: kullanıcı id'si (sayıysa), email veya `deviceId` — parça eşleşme, harf duyarsız |
| `language` | Dile göre filtre |
| `appVersion` | Sürüme göre filtre |
| `appName` | Client / uygulamaya göre filtre |
| `registered` | `true` sadece hesabı olanlar, `false` sadece anonim cihazlar |
| `page`, `size` | Sayfalama (`size` en fazla `maxPageSize`) |

Liste `lastSeenAt` azalan sırada döner; hesabı olmayan kayıtlar anonim cihazlardır
(`accountId`, `email`, `displayName` null).

### Hesap tablosunu bağlamak

Kütüphane hesapların nerede tutulduğunu bilmez. Tablo ve kolonlar parametre olarak verilince
email ile arama SQL join'i ile çalışır:

```kotlin
val me = UserMeConfig(
    database = db,
    accountId = { it.currentAccount()?.accountId },
    accounts = AccountsSource(
        table = auth.accounts,
        id = auth.accounts.id,
        email = auth.accounts.email,
        displayName = auth.accounts.displayName
    )
)
```

Verilmezse liste yine çalışır; `email` ve `displayName` null döner, email ile arama yapılmaz.

## Oturum kime ait?

| Durum | Sonuç |
| --- | --- |
| Giriş yok | Oturum `deviceId`'ye bağlı; yoksa oluşturulur, varsa güncellenir |
| Giriş var, hesabın oturumu yok | Cihazın anonim oturumu hesaba bağlanır, geçmişi korunur |
| Giriş var, hesabın oturumu var | Hesabın oturumu güncellenir; cihazın anonim geçmişi (açılış sayısı, ilk görülme, metadata) hesaba eklenip anonim kayıt silinir |

Hesap başına tek oturum tutulur; kullanıcı başka cihazdan girerse `deviceId` son kullanılan cihaz olur.

## Kod üzerinden kullanım

Kendi uçlarını ve cevap şeklini korumak istiyorsan HTTP ucu yerine doğrudan çağır:

```kotlin
val sessions = UserMeSessions(me)
val session = sessions.upsert(SessionRequest(deviceId = deviceId, platform = platform), accountId = null)
```

Liste sorgusu da koddan çağrılabilir:

```kotlin
val users = UserMeUsers(me)
val (rows, total) = users.query(UserQuery(q = "ali@", language = "tr"), page = 1, size = 25)
```

Okuma tarafında `sessions.findByDevice(...)` / `findByAccount(...)` ya da doğrudan `me.sessions`
tablosu üzerinde kendi Exposed sorguların da yazılabilir.

## Tablo

`migrate()` tek tablo oluşturur: `<prefix>sessions` — `device_id`, `account_id`, `platform`,
`app_version`, `app_name`, `language`, `city`, `latitude`, `longitude`, `metadata`,
`open_count`, `first_seen_at`, `last_seen_at`.

`account_id` bilerek düz bir tamsayıdır, foreign key değildir: hesap tablosu başka bir
kütüphanede olabilir ya da hiç olmayabilir.

Uygulamaya özel alanlar (premium, izinler...) ya `metadata` JSON'una konur ya da projenin
kendi tablosunda tutulur — SQL'de filtrelenmesi gerekiyorsa ikincisi daha uygundur.

## Sürüm notu

`2.0.0` iki kırıcı değişiklik getirdi:

1. `POST /me/session` artık `userMeRoutes` içinde değil, ayrı `sessionWriteRoutes` ile monte
   edilir. 1.x'teki davranışı korumak için ikisini birden monte et:
   ```kotlin
   routing {
       userMeRoutes(me)
       sessionWriteRoutes(me)
   }
   ```
2. `GET /me` okuma anında kaydı tazeleyebiliyor (`readTracking`). Varsayılan kapalıdır,
   yani vermezsen davranış 1.x ile aynıdır.

Ayrıca eklendi: projeye özel tabloları listeye bağlayan `ExtraSource`, ve `GET /me` sorgusundan
profil güncelleme. Şema değişikliği yok.

`1.1.0` kullanıcı listesini ekledi (`GET /users`, `/users/filters`, `UserMeUsers`, `AccountsSource`).
`GET /me` artık oturumun yanında hesap bilgisini de içeren `UserListItem` döner; önceki sürümde
sadece oturum dönüyordu.

## Geliştirme

```bash
./gradlew build
```
