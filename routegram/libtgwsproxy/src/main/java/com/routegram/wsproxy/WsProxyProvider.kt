package com.routegram.wsproxy

import android.content.Context
import android.util.Log
import com.routegram.core.ProxyConfig
import com.routegram.core.ProxyProvider

/**
 * [ProxyProvider] на базе нативного движка ws-proxy (libtgwsproxy.so).
 *
 * Поднимает движок IN-PROCESS на 127.0.0.1:port (без foreground-сервиса и
 * уведомления — ради «скрыто от пользователя») и возвращает [ProxyConfig],
 * который applier скормит клиенту через setProxySettings.
 *
 * @param context нужен для cacheDir — движок кэширует туда CF-данные.
 */
class WsProxyProvider(
    private val context: Context,
    private val settings: WsProxySettings = WsProxySettings()
) : ProxyProvider {

    override suspend fun obtain(): ProxyConfig? = try {
        // 0. Чистый (ре)старт: если движок уже крутится (например, после смены сети его
        //    upstream-пул мёртв) — гасим и поднимаем заново. На первом старте stop() = no-op.
        runCatching { WsProxyNative.stop() }

        // 1. Конфигурируем движок перед стартом.
        WsProxyNative.setPoolSize(settings.poolSize)
        WsProxyNative.setCfProxyCacheDir(context.cacheDir.absolutePath)
        WsProxyNative.setCfProxyConfig(settings.cfEnabled, settings.cfPriority, settings.cfDomain)

        // 2. Стартуем. rc: 0 — ok, -1 — уже запущен (тоже ок), -3 — bind fail.
        val rc = WsProxyNative.start(
            settings.bindIp, settings.port, settings.dcIps, settings.secret, settings.verbose
        )
        // 3. Секрет с префиксом — клиент использует его как MTProto secret.
        val secret = WsProxyNative.secretWithPrefix()
        when {
            rc != 0 && rc != -1 -> { Log.e(TAG, "StartProxy failed rc=$rc"); null }
            secret.isNullOrEmpty() -> { Log.e(TAG, "GetSecretWithPrefix null/empty"); null }
            else -> {
                Log.d(TAG, "ws-proxy up on ${settings.bindIp}:${settings.port}")
                ProxyConfig(address = settings.bindIp, port = settings.port, secret = secret)
            }
        }
    } catch (t: Throwable) {
        // Нативная граница: любой сбой (.so не найден, символ, паника) → null,
        // клиент откатится на прямое подключение, без краша.
        Log.e(TAG, "ws-proxy obtain failed", t)
        null
    }

    private companion object {
        const val TAG = "Routegram"
    }
}
