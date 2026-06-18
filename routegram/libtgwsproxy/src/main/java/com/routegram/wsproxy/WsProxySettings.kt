package com.routegram.wsproxy

/**
 * Настройки ws-proxy провайдера. Дефолты подобраны так, чтобы работать
 * «из коробки» (значения — как в ProxyService из submodule).
 *
 * Позже эти поля будет править вкладка настроек (persist в SharedPreferences),
 * но провайдер про источник значений не знает — получает готовый объект.
 */
data class WsProxySettings(
    val bindIp: String = "127.0.0.1",
    val port: Int = 1443,
    /** Список Telegram DC IP; пусто → движок использует встроенные. */
    val dcIps: String = "",
    /** Размер пула соединений (движок клампит в 2..16). */
    val poolSize: Int = 4,
    val cfEnabled: Boolean = true,
    val cfPriority: Boolean = true,
    /** Свой Cloudflare Worker/домен; пусто → встроенные. */
    val cfDomain: String = "",
    /** MTProto secret; пусто → движок генерит сам. */
    val secret: String = "",
    val verbose: Boolean = false
)
