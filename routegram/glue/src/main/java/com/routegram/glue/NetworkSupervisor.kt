package com.routegram.glue

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.routegram.core.ProxyController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.ConnectionsManager

/**
 * Надзор за обходом по РЕАЛЬНОМУ состоянию соединения клиента — детерминированный
 * автомат (по образцу телеграмного ProxyRotationController), без сетевых колбэков
 * и магических дебаунсов.
 *
 * НЕинвазивен к клиенту: только подписка на публичный
 * [NotificationCenter.didUpdateConnectionState] + чтение [ConnectionsManager.getConnectionState].
 * Ни один upstream-файл не меняется — бамп клиента это не задевает.
 *
 * Правило:
 *   Connected                       -> отменить отложенный reestablish, сбросить эскалацию
 *   WaitingForNetwork               -> отменить (оффлайн — движок чинить бессмысленно)
 *   Connecting | ConnectingToProxy  -> если ещё не запланировано, через T пересобрать движок
 * T эскалирует 10→15→30→60с (как ROTATION_TIMEOUTS у Telegram), сброс на Connected.
 */
class NetworkSupervisor(
    private val controller: ProxyController
) : NotificationCenter.NotificationCenterDelegate {

    private val handler = Handler(Looper.getMainLooper())
    private var observedAccount = -1
    private var pending = false
    private var escalation = 0

    private val reestablish = Runnable {
        pending = false
        if (escalation < TIMEOUTS.lastIndex) escalation++   // следующий раз ждём дольше
        Log.d(TAG, "reestablish: клиент завис → restart движка")
        controller.restart()
        evaluate()   // всё ещё плохо — перепланируем с увеличенным T; стало Connected — отменится
    }

    /** Запустить обход и включить надзор по состоянию соединения. */
    fun start() {
        controller.start()
        observedAccount = UserConfig.selectedAccount
        NotificationCenter.getInstance(observedAccount)
            .addObserver(this, NotificationCenter.didUpdateConnectionState)
        evaluate()
    }

    override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
        if (id == NotificationCenter.didUpdateConnectionState && account == observedAccount) {
            evaluate()
        }
    }

    private fun evaluate() {
        val state = ConnectionsManager.getInstance(observedAccount).connectionState
        when {
            state == ConnectionsManager.ConnectionStateConnected -> {
                escalation = 0
                cancel()
            }
            state == ConnectionsManager.ConnectionStateWaitingForNetwork -> cancel()
            // «не Connected и не оффлайн» = Connecting | ConnectingToProxy
            else -> if (!pending) {                 // не сбрасываем таймер на переходах «плохо→плохо»
                pending = true
                val t = TIMEOUTS[escalation] * 1000L
                Log.d(TAG, "state=$state → reestablish через ${t / 1000}с")
                handler.postDelayed(reestablish, t)
            }
        }
    }

    private fun cancel() {
        if (pending) {
            pending = false
            handler.removeCallbacks(reestablish)
        }
    }

    private companion object {
        const val TAG = "Routegram"
        // Секунды; эскалация, как ROTATION_TIMEOUTS у Telegram (5/10/15/30/60).
        val TIMEOUTS = intArrayOf(10, 15, 30, 60)
    }
}
