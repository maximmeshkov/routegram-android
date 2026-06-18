package com.routegram.wsproxy

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

/**
 * Низкоуровневый JNA-биндинг к libtgwsproxy.so (C ABI крейта `tgwsproxy`).
 * Сигнатуры — по образцу NativeProxy.kt из submodule, но в НАШЕМ пакете,
 * чтобы не тянуть их Compose-приложение.
 */
internal interface ProxyLibrary : Library {
    fun StartProxy(host: String, port: Int, dcIps: String, secret: String, verbose: Int): Int
    fun StopProxy(): Int
    fun SetPoolSize(size: Int)
    fun SetCfProxyCacheDir(cacheDir: String)
    fun SetCfProxyConfig(enabled: Int, priority: Int, userDomain: String)
    fun SetSecret(secret: String)
    fun GetSecretWithPrefix(): Pointer?
    fun GetStats(): Pointer?
    fun FreeString(p: Pointer)

    companion object {
        // Грузим один раз лениво. JNA сам делает System.loadLibrary("tgwsproxy").
        val INSTANCE: ProxyLibrary by lazy {
            Native.load("tgwsproxy", ProxyLibrary::class.java) as ProxyLibrary
        }
    }
}

/**
 * Удобная Kotlin-обёртка над [ProxyLibrary]: булевы вместо int-флагов,
 * авто-освобождение C-строк (FreeString).
 */
object WsProxyNative {

    /** 0 — ok, -1 — уже запущен, -3 — bind fail. */
    fun start(host: String, port: Int, dcIps: String, secret: String, verbose: Boolean): Int =
        ProxyLibrary.INSTANCE.StartProxy(host, port, dcIps, secret, if (verbose) 1 else 0)

    /** 0 — ok, -1 — не был запущен. */
    fun stop(): Int = ProxyLibrary.INSTANCE.StopProxy()

    fun setPoolSize(size: Int) = ProxyLibrary.INSTANCE.SetPoolSize(size)

    fun setCfProxyCacheDir(dir: String) = ProxyLibrary.INSTANCE.SetCfProxyCacheDir(dir)

    fun setCfProxyConfig(enabled: Boolean, priority: Boolean, userDomain: String) =
        ProxyLibrary.INSTANCE.SetCfProxyConfig(if (enabled) 1 else 0, if (priority) 1 else 0, userDomain)

    fun setSecret(secret: String) = ProxyLibrary.INSTANCE.SetSecret(secret)

    /** Секрет с префиксом ("dd…" или "ee…+domain_hex") — его отдаём клиенту как MTProto secret. */
    fun secretWithPrefix(): String? = readAndFree { ProxyLibrary.INSTANCE.GetSecretWithPrefix() }

    /** Строка статистики движка — наш Level-2 счётчик (байты через прокси). */
    fun stats(): String? = readAndFree { ProxyLibrary.INSTANCE.GetStats() }

    private inline fun readAndFree(get: () -> Pointer?): String? {
        val p = get() ?: return null
        val s = p.getString(0)
        ProxyLibrary.INSTANCE.FreeString(p)
        return s
    }
}
