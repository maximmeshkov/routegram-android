package com.routegram.glue

import android.util.Log
import com.routegram.core.ProxyApplier
import com.routegram.core.ProxyConfig
import org.telegram.tgnet.ConnectionsManager

/**
 * Реализация "розетки" [ProxyApplier] в клиент Telegram.
 *
 * Единственное место в нашем коде, которое вызывает [ConnectionsManager].
 * Если его API изменится при бампе клиента — правка только здесь.
 */
class TelegramProxyApplier : ProxyApplier {

    override fun apply(config: ProxyConfig?) {
        if (config == null) {
            // Шаг 2: заглушка отдаёт null. НАМЕРЕННО ничего не применяем —
            // не трогаем прокси-настройки пользователя, поведение клиента = как было.
            Log.d(TAG, "apply(null) — инертно, прокси клиента не трогаем")
            return
        }
        Log.d(TAG, "apply: включаю прокси ${config.address}:${config.port}")
        ConnectionsManager.setProxySettings(
            true,
            config.address,
            config.port,
            config.username,
            config.password,
            config.secret
        )
    }

    companion object {
        const val TAG = "Routegram"
    }
}
