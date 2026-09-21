# user-me-lib

Ktor + Exposed tabanlı, **kullanıcının kendine dair bilgilerini yöneten kütüphane**:
uygulama açılışındaki oturum/cihaz kaydı ve "benim bilgilerim" ucu.

- Giriş yoksa oturum `deviceId`'ye bağlanır.
- Giriş varsa oturum **hesaba** bağlanır; cihazın o ana kadarki anonim geçmişi hesaba devredilir.
- Kayıt / giriş / token işleri burada **yoktur**, onlar [auth-lib](https://github.com/Furkanaksu/auth-lib)'de.
- Admin listeleri, istatistik, premium gibi uygulamaya özel işler de yoktur; onlar projede kalır.

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
implementation("com.github.Furkanaksu:user-me-lib:1.0.0")
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

| Metot | Yol | Açıklama |
| --- | --- | --- |
| POST | `/me/session` | Uygulama açılışında oturum kaydı |
| GET | `/me` | Giriş varsa hesabın oturumu, yoksa `?deviceId=` ile cihazın oturumu |

`POST /me/session` gövdesi — sadece `deviceId` zorunlu:

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

Okuma tarafında `sessions.findByDevice(...)` / `findByAccount(...)` ya da doğrudan `me.sessions`
tablosu üzerinde kendi Exposed sorguların (admin listesi, filtre, istatistik) yazılabilir.

## Tablo

`migrate()` tek tablo oluşturur: `<prefix>sessions` — `device_id`, `account_id`, `platform`,
`app_version`, `app_name`, `language`, `city`, `latitude`, `longitude`, `metadata`,
`open_count`, `first_seen_at`, `last_seen_at`.

`account_id` bilerek düz bir tamsayıdır, foreign key değildir: hesap tablosu başka bir
kütüphanede olabilir ya da hiç olmayabilir.

Uygulamaya özel alanlar (premium, izinler...) ya `metadata` JSON'una konur ya da projenin
kendi tablosunda tutulur — SQL'de filtrelenmesi gerekiyorsa ikincisi daha uygundur.

## Geliştirme

```bash
./gradlew build
```
