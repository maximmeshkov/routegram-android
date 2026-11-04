package com.routegram.glue

import android.util.Log
import com.routegram.core.ProxyApplier
import com.routegram.core.ProxyConfig
import org.telegram.tgnet.ConnectionsManager
import org.telegram.utils.proxy.ProxySettings

/**
 * Реализация "розетки" [ProxyApplier] в клиент Telegram.
 *
 * Единственное место в нашем коде, которое вызывает [ConnectionsManager].
 * Если его API изменится при бампе клиента — правка только здесь.
 */
class TelegramProxyApplier : ProxyApplier {

    override fun apply(config: ProxyConfig?) {
        if (config == null) {
            // Выключить прокси у клиента (прямое подключение) — нужно супервайзеру,
            // чтобы при отсутствии нормального инета tgnet показал родное «нет сети».
            Log.d(TAG, "apply(null) — выключаю прокси (setProxySettings false)")
            ConnectionsManager.setProxySettings(false, null)
            return
        }
        Log.d(TAG, "apply: включаю прокси ${config.address}:${config.port}")
        ConnectionsManager.setProxySettings(
            true,
            ProxySettings.builder()
                .setType(if (config.secret.isEmpty()) ProxySettings.Type.SOCKS5 else ProxySettings.Type.MTPROTO)
                .setAddress(config.address)
                .setPort(config.port)
                .setUser(config.username)
                .setPassword(config.password)
                .setSecret(config.secret)
                .build()
        )
    }

    companion object {
        const val TAG = "Routegram"
    }
}
