# Исследование прокси-ядра RouteGram

> Итог для 0.2.0: оригинальный Flowseal как закреплённая внешняя зависимость,
> наша обёртка сбоку; Telegram 13.1.1 целиком со своими зависимостями.
> Индивидуальные обновления библиотек Telegram запрещены пользователем.
> Ниже сохранён ход исследования; актуальные результаты — в DEPENDENCY_REVIEW.md.

Исследование выполнено 2026-11-03. Пользователь одобрил прямое встраивание
Flowseal с CPython; реализация и проверки продолжены 2026-11-04.
Текущее устройство модуля и команды проверки — в [libtgwsproxy/README.md](libtgwsproxy/README.md).
Ниже сохранены результаты выбора, включая альтернативы, которые не внедрялись.
После исследования пользователь разрешил поднять минимум приложения до Android 9
(API 28). Упоминания обязательного API 21 ниже относятся к прежним условиям;
исходный выбор Flowseal остаётся в силе.

## Уточнённая задача и вывод

Пользователь уточнил: нужен **внешний поддерживаемый модуль tgwsproxy**, который
можно быстро обновлять. Приложение после открытия должно работать автоматически.
Ручные серверы/подписки, новый UI, Xray/sing-box и свой протокол
не входят в текущую задачу. Возможность опытному пользователю добавить свой
прокси относится к будущим задачам. Малопопулярный upstream с риском прекращения
поддержки пользователя не устраивает. **Regstar2 исключён пользователем**:
после повторного предложения он повторно возразил против практически
неизвестного проекта. Не предлагать его снова как основной выбор и не
подключать без явного пересмотра этого решения пользователем.

## Рекомендация с учётом требования к устойчивости upstream

**Основное предложение — оригинальный [Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy)
в отдельном Android-модуле вместе с CPython.** На 03.11.2026 у него 10 911 звёзд,
репозиторий не архивирован, последнее обновление — 01.11.2026;
исследована версия 1.11.4, SHA `2085861957070eeea07990a522b29f42e5230210`.
Эти значения повторно проверены через GitHub API и исходный `proxy/__init__.py`.

Причина выбора: пользователь уже отверг зависимость от малоизвестного порта.
Прямое встраивание оригинала убирает дополнительного сопровождающего,
который должен переносить изменения протокола. Наш модуль упаковывает runtime,
настраивает локальный listener и управляет запуском/остановкой; пакет `proxy`
берётся без изменений. Обновляются закреплённые версии upstream и runtime,
затем проверяются контракт запуска и соединение. Пользователь принял это решение;
реализация находится в `libtgwsproxy`.

Цена: первая Android-упаковка сложнее Go-варианта ниже, появляются Python runtime
и его сборка. Размер APK/RAM не измерен. Официальный CPython 3.13.18 содержит
API 21, нужные ABI и 16 KB linker flags в Android build scripts, однако именно
связку RouteGram + Flowseal + CPython ещё предстоит собрать и испытать.
Подробная проверка зависимостей, lifecycle и криптобэкенда приведена в
[flowseal-embedding-research.md](../build/routegram-0.1.7/flowseal-embedding-research.md).

## Альтернатива с более простой нативной упаковкой

**Для более простой нативной упаковки исследован внешний
[spatiumstas/tg-ws-proxy-go](https://github.com/spatiumstas/tg-ws-proxy-go),
релиз0.12, SHA5cd535356c33dd2a18d116390f61f63e1badcf2c, в собственной Android-упаковке.**
Собственным остаётся только модуль запуска/остановки; протокол, MTProto listener,
WS/CF, обновление доменов и исправления ядра берутся неизменёнными из upstream.
Это предложение, ещё не принятое пользователем и не реализованное в RouteGram.

Отсутствие готового AAR не требует собственного форка ядра: CLI уже является
готовым интерфейсом. Модуль собирает Android executable из закреплённого SHA,
включает его в APK и запускает автоматически. Пользователь не видит терминал,
не устанавливает второе приложение и не настраивает сервер.

Что отличает кандидата от отвергнутого малозаметного core:

- На03.11.2026: **215 звёзд,12 форков**, релиз0.12 от30.10.2026.
  [GitHub API](https://api.github.com/repos/spatiumstas/tg-ws-proxy-go).
- Релизы [0.10](https://github.com/spatiumstas/tg-ws-proxy-go/releases/tag/0.10),
  [0.11-rev3](https://github.com/spatiumstas/tg-ws-proxy-go/releases/tag/0.11-rev3)
  и [0.12](https://github.com/spatiumstas/tg-ws-proxy-go/releases/tag/0.12)
  синхронизируются с Flowseal1.10.1/1.11.2/1.11.3 и исправляют CF, TLS,
  connection reset и32-битный transport. Это поддержка ядра, а не только UI.
- Последний [CI](https://github.com/spatiumstas/tg-ws-proxy-go/actions/runs/41099510888)
  успешен; workflow содержит race-тесты,386-тесты,vet,govulncheck.
- В [Releases API](https://api.github.com/repos/spatiumstas/tg-ws-proxy-go/releases?per_page=5)
  у двух aarch64_cortex-a53 файлов0.12 —22581 и12231 скачивание.
  Это счётчики файлов, **не число пользователей**. Пакеты с расширением.apk
  относятся к OpenWrt, **не Android**.
- Независимые потребители проверены по коду:
  [Zapret-Manager](https://github.com/StressOzz/Zapret-Manager/blob/0935cf424d81b4b59c682d8b2db6905f2d9ee12c/files/proxy_zms.sh)
  и [FlintRoute](https://github.com/Zaqvierm/FlintRoute/blob/710ab5cebdbe5db2986dfd57069a4ab4c7fc4faa/internal/component/catalog.go)
  получают этот движок из release-пакетов.

**Цена и ограничения известны:** один основной сопровождающий; это не проект
с большой командой и не гарантия будущей поддержки. Переход с Rust/JNA на
управляемый Go-процесс требует нового build toolchain и проверки Android.
Штатная upstream CI-матрица роутерная; конкретный Android-бинарь ещё не собран.
Сокращение minSdk/ABI не предлагается: сохранить их — обязательная проверка
до принятия миграции в релиз.

Упаковка через `nativeLibraryDir/libtgwsproxy_exec.so` и ProcessBuilder
опирается на [реализацию Shadowsocks Android](https://github.com/shadowsocks/shadowsocks-android/blob/ae28fd91931fe4d2d5aab044de9ceaf9ed07ad56/core/src/main/java/com/github/shadowsocks/bg/ProxyInstance.kt#L119).
Нужно включить извлечение этого native artifact из APK; нельзя скачивать и
исполнять его из writable filesDir. Основной interface RouteGram остаётся
`ProxyProvider.obtain(): ProxyConfig?`, локальный MTProto127.0.0.1:1443,
secret `dd...`. Существующие ProxyController/NetworkSupervisor управляют
подключением. Модуль владеет дочерним процессом, готовностью порта и остановкой.

**Следующий бамп:** обновить один пин upstream, пересобрать неизменённый CLI,
проверить CLI-контракт и сеть. Не делать собственного Go SDK, C ABI и патчей
протокола. Для первого выпуска нужны API21/3ABI,16KB,TLS/DNS,stop/restart,
смена сети, реальное соединение, сообщения и медиа. Go пока не установлен,
новые зависимости в проект не добавлены. Детальная проверка со ссылками:
[ecosystem-research.md](../build/routegram-0.1.7/ecosystem-research.md).

### Вариант с прямой зависимостью от большого оригинального upstream

Чтобы исключить ещё одного сопровождающего порта, основным вариантом предлагается
**оригинальный Flowseal** (около10.9тыс.звёзд) неизменённым пакетом внутри
Android-модуля с официальным CPython. Это собственная упаковка, не переписывание
протокола. CPython3.13.18 имеет Android API21 и нужные ABI; готовыйAAR и
испытанный crypto/bootstrap/lifecycle ещё нужны, цена первичной упаковки выше.
Размер APK/RAM не измерен. Подробности:
[flowseal-embedding-research.md](../build/routegram-0.1.7/flowseal-embedding-research.md).
Прежняя запись «Python запрещён пользователем» была неверной интерпретацией
агента: пользователь такого запрета не давал. Добавление runtime требует
отдельного решения, а не автоматического изменения приложения.

GOST тоже исследован как зрелый транспорт, но не рекомендуется вместо
tgwsproxy без дополнительных работ: прямой WSS-probe до Telegram прошёл,
при разбиении initial64bytes обнаружены таймауты; сам GOST/Android не испытан.
Исходники, измерения и границы результата:
[gost-module-research.md](../build/routegram-0.1.7/gost-module-research.md).

## Исходное ядро и ранее проверенные ограничения

Текущий upstream **amurcanov/tg-ws-proxy-android архивирован**. Его последний
релиз v1.2.3 совпадает с закреплённым у RouteGram коммитом `1ded446`, но после
релиза в `main` появились ещё 30 коммитов. Поэтому «последний релиз» не означает
«последние исправления». Последний `main` — `540e588d15fbdf806f694fbe0b952ff044d39a07`
от 2026-10-06. Архивация и время последнего push проверены через
[GitHub API](https://api.github.com/repos/amurcanov/tg-ws-proxy-android), релиз —
на [странице v1.2.3](https://github.com/amurcanov/tg-ws-proxy-android/releases/tag/v1.2.3),
история — на [коммите main](https://github.com/amurcanov/tg-ws-proxy-android/commit/540e588d15fbdf806f694fbe0b952ff044d39a07).

Среди рассмотренных кандидатов **не подтверждена поддерживаемая замена,
которую можно подключить без адаптации и сохранить Rust, нынешний C ABI,
Android 5+ и три собираемые архитектуры**. Это ограниченный вывод по
проверенным проектам, а не доказательство отсутствия других реализаций.

Найдено более точное техническое совпадение — **Regstar2/tgwsproxy-core**:
готовая отдельная Android-библиотека, а не извлечение кода из приложения.
Уточнение прежнего вывода: её minSdk **21**, ограничение API26 относится к
самостоятельному приложению Regstar2. Но core имеет статус Prototype, одну
звезду и только ARM64; это не решает высказанное пользователем опасение о
долговременном сопровождении. Рекомендация Regstar2 отозвана полностью.
Последующее предложение «я бы принял этот риск» противоречило требованию
пользователя и не является действующим решением проекта.

Готовый AAR, одновременно закрывающий все требования, не подтверждён.
Расширенный поиск выше даёт другой способ удовлетворить запрос: отдельная
Android-упаковка неизменённого поддерживаемого CLI. Подменять внешний upstream
собственным сопровождением amurcanov не предлагается.

## Исключённый кандидат: Regstar2/tgwsproxy-core

Проверена ревизия
[`515fc50af9797c69722f14409dedcaf96868d9ce`](https://github.com/Regstar2/tgwsproxy-core/commit/515fc50af9797c69722f14409dedcaf96868d9ce)
от 2026-10-31. [README](https://github.com/Regstar2/tgwsproxy-core/blob/515fc50af9797c69722f14409dedcaf96868d9ce/README.md)
и [Gradle](https://github.com/Regstar2/tgwsproxy-core/blob/515fc50af9797c69722f14409dedcaf96868d9ce/core/build.gradle.kts)
подтверждают отдельный AAR, minSdk21, compileSdk35, JNA5.14.0 и только arm64-v8a.
Maven/GitHub Packages publication нет по README; список GitHub releases пуст.
Это библиотека, которую сейчас требуется собирать из закреплённых исходников.

Публичный Kotlin-интерфейс
[`TgWsProxyCore`](https://github.com/Regstar2/tgwsproxy-core/blob/515fc50af9797c69722f14409dedcaf96868d9ce/core/src/main/java/io/github/regstar2/tgwsproxy/core/TgWsProxyCore.kt)
даёт start(config), stop(), status(); внутри уже есть JNA-адаптер. Core не
содержит Activity, Service, Telegram-классов или настроек приложения.
start принимает 32 hex-символа локального секрета; готовность listener и
готовность внешнего маршрута различаются в статусах. Нельзя трактовать один
успешный start как проверенное соединение Telegram.

По [provenance](https://github.com/Regstar2/tgwsproxy-core/blob/515fc50af9797c69722f14409dedcaf96868d9ce/docs/provenance.md)
runtime синхронизирован с Regstar2 Android v1.11.6. На этой ревизии
[CI завершён успешно](https://github.com/Regstar2/tgwsproxy-core/actions/runs/41240858496):
[скрипт](https://github.com/Regstar2/tgwsproxy-core/blob/515fc50af9797c69722f14409dedcaf96868d9ce/scripts/ci.ps1)
выполняет Go-тесты, Kotlin unit tests и сборку debug AAR. Это upstream-проверки,
не запуск RouteGram на устройстве и не проверка сети пользователя.

Есть реальный потребитель:
[telegram-wsp v13.1.1-wsp.3](https://github.com/Regstar2/telegram-wsp/releases/tag/v13.1.1-wsp.3)
опубликован 2026-10-31 на том же core SHA и Telegram13.1.1. В предыдущем выпуске
использовался core8016d1b. В
[сравнении этих core-ревизий](https://github.com/Regstar2/tgwsproxy-core/compare/8016d1b56210e8edc69da9d637416b70b30971a8...515fc50af9797c69722f14409dedcaf96868d9ce)
нет изменений Kotlin-интерфейса/Gradle: последний бамп действительно касается
native-кода. Это пример обновления без смены интерфейса, не гарантия всех
будущих бампов. Сам upstream обозначает стадию **Prototype**.

На дату проверки [core](https://api.github.com/repos/Regstar2/tgwsproxy-core)
имел 1 звезду, а
[исходное Android-приложение](https://api.github.com/repos/Regstar2/tg-ws-proxy-android)
95. Нельзя приписывать core популярность Flowseal или считать наличие свежего
коммита обещанием дальнейшей поддержки. Вариант технически подходит по форме
модуля, но владельцем проекта отклонён и в RouteGram не подключён.

## Дополнительный поиск после замечания о сопровождении

Звёзды зафиксированы 2026-11-03; они показывают интерес, не гарантию поддержки.
Кроме метаданных проверялись исходники, история ядра и способ упаковки.

| Кандидат | Проверенное состояние | Соответствие текущей задаче |
|---|---|---|
| [Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy), 10910 звёзд | Оригинал на Python; main2085861 от01.11; [pyproject](https://github.com/Flowseal/tg-ws-proxy/blob/2085861957070eeea07990a522b29f42e5230210/pyproject.toml) и модуль proxy | Резерв: неизменённый пакет+официальныйCPython в Android-модуле; потребуется runtime-упаковка, см. новый раздел выше |
| [spatiumstas/tg-ws-proxy-go](https://github.com/spatiumstas/tg-ws-proxy-go), 215 | Релизный коммит5cd5353 от30.10; свежие исправления, CI и внешние потребители | Основное предложение: неизменённый CLI в собственном Android-модуле; Android-сборка/запуск ещё требуют проверки |
| [d0mhate/-tg-ws-proxy-Manager-go](https://github.com/d0mhate/-tg-ws-proxy-Manager-go), 104 | README: Go CLI, OpenWrt; последний main-коммит57a83f3 от01.05 | Не готовая Android-библиотека; свежесть ядра не подтверждена |
| [LemoLev/tgwsp-android-rewrite](https://github.com/LemoLev/tgwsp-android-rewrite), 147 | Последний релиз23.03; один app-модуль, Python/Chaquopy, min24; README сообщает баг остановки | Не подходит для надёжного обновляемого внешнего модуля |
| [EwenLoy/ByeByeDPI-x-tg](https://github.com/EwenLoy/ByeByeDPI-x-tg), 458 | Приложение обновляется, но история third-party/tg-ws-proxy-rs показывает только добавление17b7e69 от24.08 | Популярность оболочки не подтверждает дальнейшую поддержку заимствованного Rust-ядра |
| [rupleide/NetFixMobile](https://github.com/rupleide/NetFixMobile), 233 | Вложенное native/tgproxy-rust, изменения03.07/14.10, C API amurcanov с FlushPools | Не обнаружен отдельный публикуемый tgwsproxy-модуль; независимое долгосрочное сопровождение не установлено |

Подробности дополнительных проверок сохранены локально в
`build/routegram-0.1.7/crim50n-module-research.md` и
`build/routegram-0.1.7/lemolev-research.md`.
У crim50n возможна упаковка неизменённых proxy-исходников в library, но
стабильный библиотечный интерфейс не заявлен и между релизами менялся.
Это потенциальный резерв, а не уже готовая подключаемая зависимость.

## Где должна проходить интеграция

В проекте **уже есть** отдельный `:routegram:libtgwsproxy`. Не требуется новая
архитектура: Telegram → существующий glue/ProxyProvider → внешний tgwsproxy →
Telegram DC. Сохраняется автоматическая работа приложения.

Предлагаемое изменение после выбора подходящего upstream:

1. В существующем libtgwsproxy закрепить repository/ref выбранного внешнего ядра.
2. Собрать его неизменённые исходники отдельно и подключить артефакт с тонким
   адаптером к существующему `ProxyProvider.obtain(): ProxyConfig?`.
3. Скрыть lifecycle, настройки ядра, локальный secret и статус внутри модуля;
   не переносить протокол, UI другого приложения или новые настройки в Telegram.
4. Следующий бамп — смена ref, пересборка и проверка интерфейса/поддерживаемых
   ABI, start-stop-restart, реального соединения, сообщений и медиа.

Это схема интеграции, **не выполненная миграция**. Она локализует будущие
изменения, но не устраняет риск прекращения поддержки выбранного upstream.

## Фактическая интеграция RouteGram

Проверены локальные файлы:

- [`libtgwsproxy/build.gradle`](libtgwsproxy/build.gradle): `minSdkVersion 21`,
  cargo-ndk собирает `arm64-v8a`, `armeabi-v7a`, `x86_64`; JNA 5.14.0.
- [`WsProxyNative.kt`](libtgwsproxy/src/main/java/com/routegram/wsproxy/WsProxyNative.kt):
  `StartProxy(host, port, dcIps, secret, verbose)`, `StopProxy`, `SetPoolSize`,
  `SetCfProxyCacheDir`, `SetCfProxyConfig`, `SetSecret`, `GetSecretWithPrefix`,
  `GetStats`, `FreeString`.
- [`WsProxyProvider.kt`](libtgwsproxy/src/main/java/com/routegram/wsproxy/WsProxyProvider.kt):
  ядро работает внутри процесса; после старта Telegram получает MTProto secret;
  ошибки загрузки/получения прокси возвращают `null` для прямого подключения.
- [`TMessagesProj_AppStandalone/build.gradle`](../TMessagesProj_AppStandalone/build.gradle):
  приложение декларирует также `x86`, но Rust-задача эту архитектуру не собирает.
  Поддержку локального прокси на x86 нельзя считать подтверждённой.

## Проверенные кандидаты

| Проект и проверенная ревизия | Свидетельство активности | Совместимость с RouteGram | Вывод |
|---|---|---|---|
| [amurcanov](https://github.com/amurcanov/tg-ws-proxy-android/tree/540e588d15fbdf806f694fbe0b952ff044d39a07), `540e588`, 2026-10-06 | Архивирован; последний merge исправляет работу на мобильных сетях | Rust `cdylib`, прежние C-функции сохранены, но изменены сетевые значения по умолчанию | Возможная временная техническая база, не поддерживаемый upstream |
| [mr-Vestr](https://github.com/mr-Vestr/tg-ws-proxy-android/tree/7c7f1684312a76f64ed0436c5d5da06a3baa63ce), `7c7f168`, 2026-10-04 | Коммит исправлений уже включён в amurcanov | Тот же Rust-код; amurcanov впереди ровно на merge-коммит | Новая независимая поддержка после закрытия amurcanov не подтверждена |
| [BAI3110/tg-ws-proxy-cli](https://github.com/BAI3110/tg-ws-proxy-cli/tree/f80b3c774c5ea74724844416b0053820ae10e4d3), `f80b3c7`, 2026-10-17 | Свои оптимизации после amurcanov; релиз v2.0.0 от 2026-10-07 | Rust 2024, CLI executable; нет объявления `cdylib`, вход — `src/main.rs`; Android-артефакт только ARMv8 | Источник для оценки Rust-сопровождения, не библиотека для текущей JNA-обёртки |
| [Regstar2 Android app](https://github.com/Regstar2/tg-ws-proxy-android/tree/baf61c29e345a604d3915dbcec516cca5bb9a2fc), `baf61c2`, 2026-10-31 | Релиз v1.11.6, успешные CI и Release workflows | Go 1.25, `c-shared` и JNA; другой MTProto API, app штатно API26 / ARM64 | Для встраивания обнаружен отдельный core с API21; риск сопровождения остаётся, см. выше |
| [crim50n](https://github.com/crim50n/tg-ws-proxy-android/tree/558ffd6fe8206457aa15965e4f6e89f7c776a9c2), `558ffd6`, 2026-10-19 | Свои изменения прокси и релизного UI в октябре | Независимая Kotlin-реализация `TgWsProxyServer`, приложение minSdk26; текущий C ABI не предоставляет | Более глубокая замена провайдера, а не обновление нативной зависимости |

Активность mr-Vestr проверена не только по флагу `archived=false`: сравнение
[mr-Vestr:main…amurcanov:main](https://api.github.com/repos/amurcanov/tg-ws-proxy-android/compare/mr-Vestr:main...main)
показало `ahead_by=1`, `behind_by=0`, единственный коммит — merge `540e588`.
Это подтверждает включение его исправлений, но ничего не обещает о будущем
сопровождении. Для BAI3110 проверены [последние коммиты](https://api.github.com/repos/BAI3110/tg-ws-proxy-cli/commits?per_page=5),
[релизы и список артефактов](https://api.github.com/repos/BAI3110/tg-ws-proxy-cli/releases?per_page=3),
[Cargo.toml](https://github.com/BAI3110/tg-ws-proxy-cli/blob/f80b3c774c5ea74724844416b0053820ae10e4d3/Cargo.toml)
и [полное дерево файлов](https://api.github.com/repos/BAI3110/tg-ws-proxy-cli/git/trees/f80b3c774c5ea74724844416b0053820ae10e4d3?recursive=1).
Для crim50n прочитаны [Gradle-конфигурация](https://github.com/crim50n/tg-ws-proxy-android/blob/558ffd6fe8206457aa15965e4f6e89f7c776a9c2/app/build.gradle.kts)
и [реализация сервера](https://github.com/crim50n/tg-ws-proxy-android/blob/558ffd6fe8206457aa15965e4f6e89f7c776a9c2/app/src/main/java/dev/minios/tgwsproxy/proxy/TgWsProxyServer.kt).

Дополнительный адрес AvenCores/tg-ws-proxy-android вернул GitHub API 404.
Из этого нельзя заключить, что проект удалён: он мог быть переименован,
закрыт или быть недоступен текущему соединению. При выборе кандидата он
не использован. `valnesfjord/tg-ws-proxy-rs` не рассматривается как источник
для интеграции согласно явному ограничению проекта.

## Почему даже последний amurcanov требует проверки поведения

Локальные измерения:

```text
git -C routegram/third_party/tg-ws-proxy rev-list --count 1ded446..540e588
30

git -C routegram/third_party/tg-ws-proxy diff --stat 1ded446..540e588 -- src Cargo.toml Cargo.lock
10 files changed, 2708 insertions(+), 581 deletions(-)
```

Прежняя сигнатура `StartProxy` и используемые C-символы сохранены в
[`src/lib.rs`](https://github.com/amurcanov/tg-ws-proxy-android/blob/540e588d15fbdf806f694fbe0b952ff044d39a07/src/lib.rs).
Добавлены FakeTLS и дополнительные настройки. Однако в
[`src/config.rs`](https://github.com/amurcanov/tg-ws-proxy-android/blob/540e588d15fbdf806f694fbe0b952ff044d39a07/src/config.rs)
`DISABLE_SECURE` теперь инициализируется `true`; появился
`SetDisableSecure(v)`, который RouteGram не вызывает. Коммит
[`7c7f168`](https://github.com/amurcanov/tg-ws-proxy-android/commit/7c7f1684312a76f64ed0436c5d5da06a3baa63ce)
описывает исправление принудительного порта 443 и новый порт 80 по умолчанию.
Следовательно, простой бамп SHA меняет условия транспорта: выбор TLS/443
либо без TLS/80 нужно сделать явно и проверить на целевой сети. Сохранение
имён функций само по себе не доказывает одинаковое поведение.

## Ранее исследованное полное приложение Regstar2

Следующие сведения относятся к полному Android-приложению, не к готовому
tgwsproxy-core. Самостоятельное извлечение runtime из app не предлагается:
автор уже сопровождает отдельную библиотеку. Различия важны для проверки
поведения при возможной миграции, но не являются решением о её выполнении.

Фактические исходники ревизии `baf61c2`:

- [`native/tgwsproxy/go.mod`](https://github.com/Regstar2/tg-ws-proxy-android/blob/baf61c29e345a604d3915dbcec516cca5bb9a2fc/native/tgwsproxy/go.mod)
  задаёт Go 1.25.0.
- [`build-native-android.ps1`](https://github.com/Regstar2/tg-ws-proxy-android/blob/baf61c29e345a604d3915dbcec516cca5bb9a2fc/scripts/build-native-android.ps1)
  собирает `go build -buildmode=c-shared`, `GOOS=android`, `GOARCH=arm64`.
  `ApiLevel` — параметр со значением по умолчанию 26; clang-target и каталог
  результата зафиксированы на ARM64. Возможность изменить параметр **не
  подтверждает** работоспособность API21 или других архитектур.
- [`app/build.gradle.kts`](https://github.com/Regstar2/tg-ws-proxy-android/blob/baf61c29e345a604d3915dbcec516cca5bb9a2fc/app/build.gradle.kts)
  подтверждает `minSdk=26` и `abiFilters=arm64-v8a`. Это проверенная конфигурация
  upstream, а не доказательство принципиальной непереносимости его Go-кода.
- [`NativeProxy.kt`](https://github.com/Regstar2/tg-ws-proxy-android/blob/baf61c29e345a604d3915dbcec516cca5bb9a2fc/app/src/main/java/com/amurcanov/tgwsproxy/NativeProxy.kt)
  и [`tg-ws-proxy.go`](https://github.com/Regstar2/tg-ws-proxy-android/blob/baf61c29e345a604d3915dbcec516cca5bb9a2fc/native/tgwsproxy/tg-ws-proxy.go)
  показывают отдельный MTProto API:
  `StartMtProtoProxy(host, port, secret, runtimeConfig, verbose)`,
  `StopMtProtoProxy()`, `GetMtProtoProxyStatus()`.
  Уточнение после чтения `parseRuntimeConfig`: конфигурация передаётся строкой
  разделённых запятыми параметров (`@ключ=значение` и DC-записи), а не JSON.
  Существующий там `StartProxy(host, port, dcIps, verbose)` имеет **четыре**
  аргумента и не заменяет пятиаргументный MTProto-вызов RouteGram.
  Нужны новый биндинг, отображение настроек/секрета и корректная остановка
  именно MTProto runtime. Простая подмена одноимённого `.so` несовместима.
- Автообновление списка CF-доменов у Regstar2 реализовано в Android-слое:
  [`CfDomainList.kt`](https://github.com/Regstar2/tg-ws-proxy-android/blob/baf61c29e345a604d3915dbcec516cca5bb9a2fc/app/src/main/java/com/amurcanov/tgwsproxy/CfDomainList.kt),
  [источник списка Flowseal](https://github.com/Regstar2/tg-ws-proxy-android/blob/baf61c29e345a604d3915dbcec516cca5bb9a2fc/app/src/main/java/com/amurcanov/tgwsproxy/CfDomainUpdateSources.kt).
  При встраивании только ядра нужно учитывать обновление и кеш списка в нашем
  адаптере; наличие одного `StartMtProtoProxy` не доказывает автономное
  воспроизведение всех возможностей upstream-приложения.

Для тонкой интеграции сохраняемый контракт — наш `ProxyProvider` → `ProxyConfig`,
а не прежний набор C-функций. Поэтому адаптация биндинга внутри `libtgwsproxy`
не требует смены архитектуры Telegram-слоя. Для отдельного core уже есть
upstream-биндинг, поэтому свой заново писать не требуется. Настройки маршрута,
источник/кеш CF-списка и секрет остаются обязанностью host-приложения.
Android/ABI-совместимость и реальная работа в RouteGram ещё не проверены.

Релиз [v1.11.6](https://github.com/Regstar2/tg-ws-proxy-android/releases/tag/v1.11.6)
от 2026-10-31 исправляет отмену незавершённых MTProto/CF-подключений и
истощение пула доменов. На том же SHA успешно завершены
[CI](https://github.com/Regstar2/tg-ws-proxy-android/actions/runs/41237852255)
и [Release](https://github.com/Regstar2/tg-ws-proxy-android/actions/runs/41238853185).
Это доказательство upstream-проверок; интеграция с RouteGram ими не проверена.

## Состояние выпуска

Пользователь выбрал и разрешил внедрить оригинальный Flowseal с официальным
CPython в отдельном Android-модуле. Реализация заменила Rust/JNA-ядро на
Flowseal 1.11.4 / CPython 3.13.18, с небольшим C ABI адаптером через JNA 5.19.1.
Протокольные исходники Flowseal не изменены. Spatiumstas и Regstar2 не внедрялись.
Собственное сопровождение замороженного ядра не предлагается как замена
требованию поддерживаемого upstream. После первой реализации пользователь
разрешил Android 9 как минимум; приложение и модули переведены на API 28.
Имеющиеся три ABI сохранены.

Закреплены SHA и контрольные суммы. Собраны ARM64, ARMv7, x86_64; для всех
генерируемых `.so` проверены 16 KB alignment и наличие DT_NEEDED зависимостей.
На Android 5.0.2 и Android 17 (16 KB) прошли проверки AES, занятого порта,
трёх stop/start и настоящий MTProto req_pq_multi/resPQ с проверкой nonce.
Переписка/медиа и смена сети в пользовательских условиях блокировки ещё не
проверены. Состояние полной сборки 0.1.7 — в [DEPENDENCY_REVIEW.md](DEPENDENCY_REVIEW.md).
