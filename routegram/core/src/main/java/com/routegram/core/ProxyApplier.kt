package com.routegram.core

/**
 * "Розетка" в клиент Telegram.
 *
 * Реализуется НА СТОРОНЕ ФОРКА (модуль :TMessagesProj) и внутри дёргает
 * ConnectionsManager.setProxySettings(...). Благодаря этому интерфейсу
 * сам :routegram-core не зависит от Telegram-кода — поэтому обновление
 * upstream-клиента не задевает наш модуль.
 */
interface ProxyApplier {
    /**
     * Применить конфигурацию к клиенту.
     *
     * @param config конфигурация прокси, либо `null` — выключить прокси
     *               (прямое подключение).
     */
    fun apply(config: ProxyConfig?)
}
