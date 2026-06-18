package com.routegram.core

/**
 * Готовая к применению конфигурация прокси.
 *
 * Это общий "контракт" между источниками прокси и клиентом:
 * любой [ProxyProvider] (ws-proxy, парсер списков и т.д.) выдаёт именно
 * этот тип, а [ProxyApplier] превращает его в настройки сети клиента.
 *
 * Для C#-интуиции: `data class` ≈ record — неизменяемый набор полей
 * с автогенерацией equals/hashCode/copy.
 */
data class ProxyConfig(
    /** Хост: внешний прокси, либо 127.0.0.1 для локального движка (ws-proxy). */
    val address: String,
    val port: Int,
    val username: String = "",
    val password: String = "",
    /** MTProto/FakeTLS secret. Пусто — значит обычный SOCKS5 без секрета. */
    val secret: String = ""
)
