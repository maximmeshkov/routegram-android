# RouteGram

**Telegram для Android со встроенным обходом блокировок.**

Идея простая: открыть мессенджер и общаться, без поиска прокси, подписок и
настройки своего сервера. RouteGram сам следит за подключением и при необходимости
включает встроенный прокси. Всё нужное уже в приложении.

Это неофициальный клиент на базе [Telegram for Android](https://github.com/DrKLO/Telegram).

**Android 9+ · RouteGram 0.2.0 · Telegram 13.1.1 · tg-ws-proxy 1.11.4**

[Скачать опубликованные сборки](https://github.com/maximmeshkov/routegram-android/releases)
· [Что нового](routegram/CHANGELOG.md)
· [Сборка из исходников](#сборка-из-исходников)

## Установка

Скачайте APK со [страницы релизов](https://github.com/maximmeshkov/routegram-android/releases),
установите и войдите в свой аккаунт. Отдельное приложение для прокси не нужно.
RouteGram можно поставить рядом с официальным Telegram.

**0.2.0 сейчас тестируем перед публикацией.** Если вам передали тестовый
`RouteGram-0.2.0.apk`, его можно установить поверх нашей 0.1.6, сохранив данные.
Удалять предыдущую версию не нужно.

## Что нового в 0.2.0

- Обновили базу до Telegram 13.1.1.
- Перешли на оригинальный [Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy).
  Он подключён отдельным модулем, чтобы дальше было проще обновляться.
- Подняли минимальную версию Android до 9.
- Исправили пропавший текст в тёмной теме.
- Добавили `by MLab` со ссылкой на репозиторий внизу настроек.

## Как выбирается подключение

Если Telegram уже работает напрямую, RouteGram оставляет соединение в покое.
Если подключиться не получается, приложение проверяет доступ во внешний
интернет и, когда проверка проходит, запускает или перезапускает локальный прокси.
Вручную выбирать сервер или нажимать «подключить» не требуется.

С «белыми списками» ситуация другая: телефон может показывать сеть, но открывать
только несколько разрешённых сервисов. Если внешняя проверка не проходит,
RouteGram отключает использование прокси и ждёт восстановления доступа.
Обход блокировки Telegram сам по себе не возвращает отсутствующий интернет.

## Что пока не готово

- **Звонки, видеозвонки и голосовые чаты отключены.** Голосовые сообщения —
  отдельная функция, это ограничение на них не распространяется.
- Добавить свой прокси через обычное меню Telegram пока нельзя:
  подключением управляет RouteGram.
- При проверке были обрывы связи в фоне. После возвращения в приложение
  соединение восстанавливалось, но причину обрывов ещё выясняем.

## Помочь с проверкой

0.2.0 уже запускали на телефоне с Android 16 и эмуляторах с Android 9 и 17.
Проверили запуск и перезапуск прокси, соединение с Telegram через WebSocket,
тёмную тему и восстановление связи после отключения сети.

Теперь нужны разные телефоны и операторы: переписка, фото и видео, переключения
между Wi-Fi и мобильной сетью, возвращение после нескольких минут в фоне.
Есть [короткий список для проверки](RouteGram-0.2.0-testing.txt).
Если что-то сломалось, полезнее всего модель телефона, версия Android,
тип сети и шаги, после которых это случилось. Коды входа и личная переписка не нужны.

Часть проверок прошла на Wi-Fi с обходом на роутере. Поэтому особенно нужны
отзывы из сетей без такой помощи. Подробности для тех, кому интересно:
[проверка сборки и движка](routegram/DEPENDENCY_REVIEW.md),
[переподключения на телефоне](routegram/RECONNECT_REVIEW.md).

## Как устроен проект

Основная работа RouteGram живёт в `routegram/`. В коде Telegram стараемся
оставлять минимум изменений, чтобы новые версии клиента не превращались
в очередную переделку всего форка.

| Где | Что делает |
|---|---|
| [routegram/core](routegram/core) | Управляет жизненным циклом прокси |
| [routegram/glue](routegram/glue) | Следит за соединением и подключает прокси к Telegram |
| [routegram/libtgwsproxy](routegram/libtgwsproxy) | Собирает оригинальный Flowseal и нашу обёртку для Android |
| [routegram/android-overlay.gradle](routegram/android-overlay.gradle) | Задаёт версию RouteGram, минимум Android и подключает модули к сборке |

Исходники Flowseal используются без изменений. Его версия, коммит и контрольная
сумма закреплены в [upstream.json](routegram/libtgwsproxy/upstream.json).
Python и нужные ему библиотеки входят в APK — пользователю ничего доустанавливать
не надо.

Telegram обновляем целиком из официального [DrKLO/Telegram](https://github.com/DrKLO/Telegram),
вместе с зависимостями выбранного выпуска. Версии его библиотек по отдельности
не меняем. Прокси обновляется независимо: меняем закреплённую версию,
собираем и проверяем обёртку по [инструкции модуля](routegram/libtgwsproxy/README.md).

## Сборка из исходников

Для сборки 0.2.0 использовались JDK 21, Android SDK 36, Build Tools 36.0.0,
NDK 27.2.12479018 и CMake 3.22.1. Ещё понадобятся Python 3 и Docker
с Linux-контейнерами: внутри контейнера собирается Python для Android.

Заберите проект вместе с подмодулями Telegram:

```bash
git clone --recursive https://github.com/maximmeshkov/routegram-android.git
cd routegram-android
```

Если репозиторий уже клонирован, подтяните подмодули командой
`git submodule update --init --recursive`.

Укажите путь к Android SDK в `local.properties` (`sdk.dir=...`). Положите свой
ключ подписи в `TMessagesProj/config/release.keystore`, а параметры
`RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD` и `RELEASE_STORE_PASSWORD` —
в пользовательский `~/.gradle/gradle.properties`.
Для распространения своей сборки также нужны
[свои api_id и api_hash](https://core.telegram.org/api/obtaining_api_id)
в [BuildVars.java](TMessagesProj/src/main/java/org/telegram/messenger/BuildVars.java)
и настройки Firebase для выбранного имени пакета.

Сборка APK из корня репозитория, в том числе из PowerShell:

```bash
java -cp gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain :TMessagesProj_AppStandalone:assembleAfatStandalone
```

Gradle сам запустит сборку прокси. Первый запуск займёт время: нужно скачать
и собрать его окружение; следующие сборки используют кеш.
Готовый APK появится в
`TMessagesProj_AppStandalone/build/outputs/apk/afat/standalone/app.apk`.

Версия RouteGram задаётся в [routegram/version.properties](routegram/version.properties)
и попадает в подвал настроек вместе со временем сборки и ссылкой `by MLab`.

## Спасибо

Проект опирается на [Telegram for Android](https://github.com/DrKLO/Telegram)
и [Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy).
В версиях 0.1.x использовался [tg-ws-proxy-android от amurcanov](https://github.com/amurcanov/tg-ws-proxy-android).
Спасибо авторам и всем, кто помогает проверять RouteGram на своих телефонах.

Код клиента распространяется под [GPL-2.0](LICENSE), Flowseal — под MIT.
Лицензии компонентов встроенного Python включены в APK.
