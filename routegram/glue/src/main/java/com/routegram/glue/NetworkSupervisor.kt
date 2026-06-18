package com.routegram.glue

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.routegram.core.ProxyController
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.ConnectionsManager

/**
 * Рантайм-надзор за обходом. Перезапускает движок:
 *  1) при смене сети (VPN on/off, wifi<->mobile) — через ConnectivityManager.NetworkCallback;
 *  2) при залипании клиента — health-poll состояния соединения Telegram.
 *
 * Без корутин: дебаунс и поллинг на главном Handler; тяжёлую работу (Stop+Start движка
 * и повторный setProxySettings) делает [ProxyController] в своей IO-области.
 */
class NetworkSupervisor(
    private val context: Context,
    private val controller: ProxyController
) {
    private val handler = Handler(Looper.getMainLooper())
    private val cm get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val restartRunnable = Runnable {
        Log.d(TAG, "supervisor restart (debounced)")
        controller.restart()
    }

    private val healthRunnable = object : Runnable {
        private var badSinceMs = 0L
        override fun run() {
            checkHealth()
            handler.postDelayed(this, HEALTH_POLL_MS)
        }

        private fun checkHealth() {
            // Нет активной сети — клиент офлайн «по-честному», рестарт движка не поможет.
            if (cm.activeNetwork == null) { badSinceMs = 0L; return }
            val connected = runCatching {
                val acc = UserConfig.selectedAccount
                ConnectionsManager.getInstance(acc).connectionState == ConnectionsManager.ConnectionStateConnected
            }.getOrDefault(true)
            if (connected) {
                badSinceMs = 0L
                return
            }
            val now = SystemClock.elapsedRealtime()
            if (badSinceMs == 0L) {
                badSinceMs = now
            } else if (now - badSinceMs > STUCK_MS) {
                Log.d(TAG, "client stuck > ${STUCK_MS}ms → restart")
                badSinceMs = 0L
                controller.restart()
            }
        }
    }

    /** Запустить обход и включить надзор. */
    fun start() {
        controller.start()
        registerNetworkCallback()
        handler.postDelayed(healthRunnable, HEALTH_POLL_MS)
    }

    private fun registerNetworkCallback() {
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = schedule("available")
            override fun onLost(network: Network) = schedule("lost")
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                cm.registerDefaultNetworkCallback(cb)
            } else {
                cm.registerNetworkCallback(NetworkRequest.Builder().build(), cb)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "registerNetworkCallback failed", t)
        }
    }

    /** Дебаунс: пачку событий смены сети схлопываем в один рестарт. */
    private fun schedule(reason: String) {
        Log.d(TAG, "net change: $reason → debounced restart")
        handler.removeCallbacks(restartRunnable)
        handler.postDelayed(restartRunnable, DEBOUNCE_MS)
    }

    private companion object {
        const val TAG = "Routegram"
        const val DEBOUNCE_MS = 1500L
        const val HEALTH_POLL_MS = 15_000L
        const val STUCK_MS = 20_000L
    }
}
