package com.routegram.glue

import android.content.Context
import android.net.ConnectivityManager
import android.os.SystemClock
import android.util.Log
import com.routegram.core.ProxyController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.ProxyRotationController
import org.telegram.messenger.SharedConfig
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.ConnectionsManager

/**
 * Супервайзер обхода — по СОСТОЯНИЮ клиента, с таймингом из самого Telegram.
 *
 * Триггер — `connectionState` (событие [NotificationCenter.didUpdateConnectionState]):
 *  - **Connected/Updating** → покой (ждём события; на изменение реагируем сразу).
 *  - **проблемное** (Connecting / ConnectingToProxy / WaitingForNetwork):
 *      * ПЕРВЫЙ раз (движок ещё не поднимали) → действуем СРАЗУ — быстрый подъём прокси на старте;
 *      * далее → сначала ждём **T** и перепроверяем. Если за T вернулись в Connected (например,
 *        дребезг tgnet 3→4→3 за ~200мс, или tgnet/движок сами восстановились) — в покой,
 *        движок НЕ трогаем. Это убирает ложные «connecting to proxy на пустом месте» (мы их
 *        раньше сами создавали, перезапуская движок на мгновенный дребезг). Держится дольше T →
 *        проба «есть нормальный инет» и toggle прокси.
 *
 * **T берётся из ТЕЛЕГРАМНОГО** [ProxyRotationController.ROTATION_TIMEOUTS] (с учётом выбора
 * пользователя [SharedConfig.proxyRotationTimeout], дефолт ~10с) — не из выдуманной константы,
 * привязанной к конкретному девайсу.
 *
 * Всё НЕинвазивно: публичные NotificationCenter/ConnectionsManager/ProxyRotationController +
 * чтение activeNetwork + setProxySettings.
 */
class NetworkSupervisor(
    private val context: Context,
    private val controller: ProxyController
) : NotificationCenter.NotificationCenterDelegate {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val cm get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var account = -1
    private var started = false   // сделали ли первичный (немедленный) подъём

    fun start() {
        account = UserConfig.selectedAccount
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.didUpdateConnectionState)
        scope.launch { loop() }
    }

    private suspend fun loop() {
        while (scope.isActive) {
            if (isConnected()) {
                wake.receive()                 // покой; на изменение реагируем сразу
                continue
            }
            // Не Connected. Кроме самого первого раза — НЕ дёргаемся сразу: ждём T и
            // перепроверяем. Вернулись в Connected за T (дребезг / самоисцеление) → в покой,
            // движок не трогаем. Иначе проблема настоящая → действуем.
            if (started && recoveredWithin(rotationTimeoutMs())) continue
            started = true

            val internet = cm.activeNetwork != null && ForeignProbe.hasNormalInternet()
            if (internet) {
                Log.d(TAG, "связь: ${stateName(state())} — проблема, интернет есть → перезапускаю прокси")
                controller.reestablish()
            } else {
                Log.d(TAG, "связь: ${stateName(state())} — проблема, интернета нет → выключаю прокси")
                controller.disable()
            }
        }
    }

    /** Ждать до timeoutMs; true — если стали Connected (через события wake), false — если T истёк. */
    private suspend fun recoveredWithin(timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (scope.isActive) {
            if (isConnected()) return true
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) return false
            withTimeoutOrNull(remaining) { wake.receive() }
        }
        return false
    }

    /** T из телеграмного ProxyRotationController.ROTATION_TIMEOUTS (с учётом настройки пользователя). */
    private fun rotationTimeoutMs(): Long = try {
        val list = ProxyRotationController.ROTATION_TIMEOUTS
        var idx = SharedConfig.proxyRotationTimeout
        if (idx < 0 || idx >= list.size) idx = ProxyRotationController.DEFAULT_TIMEOUT_INDEX
        list[idx] * 1000L
    } catch (t: Throwable) {
        10_000L   // как дефолт ROTATION_TIMEOUTS
    }

    private fun isConnected(): Boolean {
        val s = runCatching { ConnectionsManager.getInstance(account).connectionState }.getOrNull() ?: return false
        // Updating = подключены + синхронизируемся — тоже «подключены».
        return s == ConnectionsManager.ConnectionStateConnected || s == ConnectionsManager.ConnectionStateUpdating
    }

    private fun state(): Int = runCatching { ConnectionsManager.getInstance(account).connectionState }.getOrDefault(-1)

    private fun stateName(s: Int): String = when (s) {
        ConnectionsManager.ConnectionStateConnecting -> "Подключение"
        ConnectionsManager.ConnectionStateWaitingForNetwork -> "Нет сети"
        ConnectionsManager.ConnectionStateConnected -> "Подключено"
        ConnectionsManager.ConnectionStateConnectingToProxy -> "Подключение к прокси"
        ConnectionsManager.ConnectionStateUpdating -> "Обновление"
        else -> "?($s)"
    }

    override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
        if (id == NotificationCenter.didUpdateConnectionState && account == this.account) {
            Log.d(TAG, "связь: ${stateName(state())}")
            wake.trySend(Unit)
        }
    }

    private companion object {
        const val TAG = "Routegram"
    }
}
