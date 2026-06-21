<div align="center">

# RouteGram

**Telegram для Android со встроенным обходом блокировок —
работает сразу после установки, без настройки и отдельных приложений.**

![Android](https://img.shields.io/badge/Android-3DDC84?style=for-the-badge&logo=android&logoColor=white)
![based on Telegram](https://img.shields.io/badge/based_on-Telegram_for_Android-229ED9?style=for-the-badge&logo=telegram&logoColor=white)
![Rust engine](https://img.shields.io/badge/engine-Rust-000000?style=for-the-badge&logo=rust&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)
![License](https://img.shields.io/badge/license-GPLv3-blue?style=for-the-badge)
![Stars](https://img.shields.io/github/stars/maximmeshkov/routegram-android?style=for-the-badge&logo=github&color=ffca28&labelColor=242952)

</div>

**RouteGram** — форк официального клиента Telegram for Android со встроенным обходом блокировок. Средства обхода интегрированы в сам клиент и работают автоматически: без отдельного прокси-приложения, ручной настройки или участия пользователя. Трафик прозрачно проходит через локальный прокси-движок к дата-центрам Telegram, оставаясь в том же зашифрованном виде.

## Чем отличается от обычного tg-ws-proxy

| | Отдельное прокси-приложение | **RouteGram** |
|---|---|---|
| Установка | два приложения: прокси + Telegram | **один APK** |
| Запуск обхода | вручную, кнопкой | **автоматически, на старте** |
| Подключение к Telegram | «Применить в Telegram» / `tg://proxy` | **встроено, незаметно** |
| Видимость | уведомление в трее, экран настроек | **скрыто от пользователя** |
| Управление вкл/выкл | вручную | **сам по состоянию сети** |

## Как это работает

```
Telegram (внутри этого же приложения)
        │
        ▼
локальный прокси  127.0.0.1   ◄──  движок tg-ws-proxy (Rust, в процессе приложения)
        │
        ▼
WebSocket  ──►(фолбэк)  Cloudflare  ──►(фолбэк)  TCP
        │
        ▼
дата-центры Telegram
```

1. На старте процесса (через `ContentProvider`, без правок ядра клиента) поднимается локальный прокси-движок [tg-ws-proxy](https://github.com/amurcanov/tg-ws-proxy-android) на Rust, слушающий loopback `127.0.0.1`.
2. Клиент Telegram автоматически направляется через него (`setProxySettings`) — без участия пользователя.
3. `NetworkSupervisor` следит за состоянием соединения и сам включает/выключает обход, с таймингами, заимствованными из самого Telegram (`ProxyRotationController`).
4. Движок выбирает транспорт **каскадом** и автоматически, по тому, что реально проходит: **WebSocket** к веб-эндпоинтам Telegram → **Cloudflare-воркеры** → прямой **TCP**.

## Архитектура

Код форка вынесен в отдельные модули, чтобы независимо обновлять и базу Telegram, и движок обхода:

| Модуль | Назначение |
|---|---|
| `routegram/core` | чистые интерфейсы (`ProxyController`, получение и применение прокси-конфигурации) |
| `routegram/glue` | связка с клиентом `:TMessagesProj`: супервайзер, проба интернета, применение прокси |
| `routegram/libtgwsproxy` | нативная обёртка движка (JNA), сборка `.so` через `cargo-ndk` |
| `routegram/third_party/tg-ws-proxy` | сам движок (git submodule) |

База Telegram обновляется обычным `git merge` из upstream [DrKLO/Telegram](https://github.com/DrKLO/Telegram): форк сохраняет общую историю, поэтому при обновлении конфликтуют только кастомизированные файлы.

## Установка

1. Скачайте последний `RouteGram-*.apk` со страницы релизов.
2. Установите на Android-устройство.
3. Откройте приложение — обход активен сразу, настройка не требуется.

> [!IMPORTANT]
> При установке Google Play Protect покажет **«приложение от неизвестного разработчика»** — нажмите **Install anyway**.
> Это **не** вердикт «вредоносное ПО»: приложение подписано собственным ключом и распространяется вне Play Store, поэтому пока неизвестно Play Protect. Если раньше была установлена версия с другим ключом подписи — сначала удалите её.

> [!WARNING]
> **Звонки, видеозвонки и голосовые чаты не работают** — это ограничение MTProto-прокси, а не клиента ([issue #389](https://github.com/Flowseal/tg-ws-proxy/issues/389)). В приложении они заблокированы с пояснением. Меню управления прокси тоже отключено: клиент использует собственную систему обхода.

## Сборка

Потребуется: Android SDK, Android NDK, **Rust + `cargo-ndk`** (для нативной либы движка) и **JDK 17** (подойдёт JBR из комплекта Android Studio).

```bash
# клонировать вместе с сабмодулем движка
git clone --recursive https://github.com/maximmeshkov/routegram-android.git
# (если уже клонировали без --recursive)
git submodule update --init --recursive
```

Положите свой `release.keystore` в `TMessagesProj/config/` и укажите `RELEASE_KEY_PASSWORD` / `RELEASE_KEY_ALIAS` / `RELEASE_STORE_PASSWORD` в `gradle.properties` (или, чтобы не хранить пароль в репозитории, — в `~/.gradle/gradle.properties`). Ключ подписи в репозиторий **не коммитится**.

```bash
JAVA_HOME="<путь к Android Studio JBR>" \
  ./gradlew :TMessagesProj_AppStandalone:assembleAfatStandalone
```

Готовый APK — в `TMessagesProj_AppStandalone/build/outputs/apk/afat/standalone/`.
Версия форка задаётся в `routegram/version.properties` (изменять только там) и отображается в подвале настроек: `RouteGram vX.Y.Z (время сборки) · based on Telegram for Android …`.

## Благодарности

- [DrKLO/Telegram](https://github.com/DrKLO/Telegram) — официальный клиент Telegram for Android, на котором основан форк.
- [amurcanov/tg-ws-proxy-android](https://github.com/amurcanov/tg-ws-proxy-android) и [Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy) — движок обхода.

## Лицензия и статус

RouteGram — **неофициальный** форк и никак не связан с Telegram FZ-LLC. Основан на проектах под GPL (Telegram — GPL-2.0, tg-ws-proxy — GPL-3.0); исходный код открыт в соответствии с их лицензиями. Используется как есть, на свой страх и риск.
