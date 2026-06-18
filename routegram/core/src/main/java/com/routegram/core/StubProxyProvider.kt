package com.routegram.core

/**
 * Провайдер-заглушка: всегда возвращает `null` (прокси выключен).
 *
 * Нужен, чтобы каркас собирался и работал end-to-end, пока нет реальной
 * реализации (ws-proxy / парсер списков). Подставив его в [ProxyController],
 * получаем поведение "как без обхода" — нулевой риск для клиента.
 */
class StubProxyProvider : ProxyProvider {
    override suspend fun obtain(): ProxyConfig? = null
}
