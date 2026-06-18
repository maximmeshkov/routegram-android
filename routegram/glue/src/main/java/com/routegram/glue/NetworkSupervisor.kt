package com.routegram.glue

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import com.routegram.core.ProxyController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.ConnectionsManager

/**
 * Супервайзер обхода — по СОСТОЯНИЮ клиента.
 *
 * Триггер — само `connectionState` (событие [NotificationCenter.didUpdateConnectionState]):
 *  - **Connected/Updating** → работает, ничего не делаем (ждём следующего события, без таймера).
 *  - **проблемное** (Connecting / ConnectingToProxy / WaitingForNetwork) → проба «есть ли
 *    нормальный (иностранный) интернет»:
 *      есть → [ProxyController.reestablish] (путь существует → поднять/кикнуть движок),
 *      нет  → [ProxyController.disable]     (пути нет → tgnet сам покажет родное «нет сети»).
 *
 * Реакция на НОВУЮ проблему (из Connected) — мгновенная (событие будит покой).
 * [SETTLE_MS] — это НЕ «реакция», а длительность коннекта движка (~5–10с): быстрее кикать
 * бессмысленно (прервём его же попытку). Он же подавляет наш собственный фликер состояния
 * во время коннекта (анти-churn) и служит переспросом, если событий нет (whitelist-lift).
 *
 * Всё НЕинвазивно: публичные NotificationCenter/ConnectionsManager + чтение activeNetwork +
 * setProxySettings. Дисплей не трогаем.
 */
class NetworkSupervisor(
    private val context: Context,
    private val controller: ProxyController
) : NotificationCenter.NotificationCenterDelegate {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val cm get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var account = -1

    fun start() {
        account = UserConfig.selectedAccount
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.didUpdateConnectionState)
        scope.launch { loop() }
    }

    private suspend fun loop() {
        while (scope.isActive) {
            if (isConnected()) {
                wake.receive()                 // здоров — ждём события (на изменение реагируем сразу)
                continue
            }

            // проблемное состояние → проба и toggle
            val internet = cm.activeNetwork != null && ForeignProbe.hasNormalInternet()
            if (internet) {
                Log.d(TAG, "проблемное состояние + есть инет → прокси ON (reestablish)")
                controller.reestablish()
            } else {
                Log.d(TAG, "проблемное состояние + нет инета → прокси OFF (родной дисплей)")
                controller.disable()
            }

            // время коннекта движка: анти-churn (игнор своего фликера) + переспрос беззнакового
            // случая. На свои события состояния в этом окне НЕ реагируем (не будим раньше).
            delay(SETTLE_MS)
        }
    }

    private fun isConnected(): Boolean {
        val s = runCatching { ConnectionsManager.getInstance(account).connectionState }.getOrNull() ?: return false
        // Updating = подключены + синхронизируемся — тоже «подключены».
        return s == ConnectionsManager.ConnectionStateConnected || s == ConnectionsManager.ConnectionStateUpdating
    }

    override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
        if (id == NotificationCenter.didUpdateConnectionState && account == this.account) {
            wake.trySend(Unit)
        }
    }

    private companion object {
        const val TAG = "Routegram"
        const val SETTLE_MS = 12_000L   // длительность коннекта движка; анти-churn + переспрос
    }
}
