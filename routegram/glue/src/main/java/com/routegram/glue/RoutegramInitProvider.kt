package com.routegram.glue

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.routegram.core.ProxyController
import com.routegram.wsproxy.WsProxyProvider
import com.routegram.wsproxy.WsProxySettings

/**
 * Авто-инициализация обхода БЕЗ правки ApplicationLoader.
 *
 * Android создаёт все ContentProvider'ы на старте процесса. Используем это как
 * точку входа: собираем [ProxyController] и запускаем его.
 *
 * ВАЖНО по таймингу: onCreate провайдера срабатывает РАНЬШЕ Application.onCreate,
 * а setProxySettings внутри клиента трогает AccountInstance/UserConfig, которые
 * поднимаются как раз в ApplicationLoader.onCreate. Поэтому сам start() откладываем
 * через post в main-looper — он выполнится уже ПОСЛЕ инициализации клиента.
 */
class RoutegramInitProvider : ContentProvider() {

    // Держим ссылку, чтобы супервайзер (наблюдатель состояния соединения) жил с процессом.
    private var supervisor: NetworkSupervisor? = null

    override fun onCreate(): Boolean {
        Log.d(TAG, "RoutegramInitProvider.onCreate — каркас обхода инициализируется")

        val ctx = context ?: return false
        val controller = ProxyController(
            // Реальный движок ws-proxy in-process. verbose=false для раздаваемой сборки.
            provider = WsProxyProvider(ctx, WsProxySettings()),
            applier = TelegramProxyApplier()
        )
        val sup = NetworkSupervisor(controller)
        supervisor = sup

        Handler(Looper.getMainLooper()).post {
            Log.d(TAG, "NetworkSupervisor.start() (отложенный, после init клиента)")
            sup.start()
        }
        return true
    }

    // Провайдером данных мы не пользуемся — методы обязательны, но заглушены.
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val TAG = "Routegram"
    }
}
