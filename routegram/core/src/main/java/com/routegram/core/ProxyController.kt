package com.routegram.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * Оркестратор: единственное место, где встречаются [ProxyProvider] и [ProxyApplier].
 *
 * Два действия, которыми рулит супервайзер:
 *  - [reestablish] — включить прокси: (пере)поднять движок (provider.obtain() = Stop+Start)
 *    и применить его конфиг клиенту. Это же «кик» залипшему движку.
 *  - [disable] — выключить прокси у клиента (apply(null)); движок не трогаем.
 *
 * Коалесинг (mutex + rerun + wantOn): наложенные вызовы не стакаются, применяется
 * ПОСЛЕДНЕЕ целевое состояние.
 */
class ProxyController(
    private val provider: ProxyProvider,
    private val applier: ProxyApplier
) {
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    @Volatile private var rerun = false
    @Volatile private var wantOn = false

    /** Включить прокси: (пере)поднять движок и применить конфиг. Неблокирующий. */
    fun reestablish() { wantOn = true; trigger() }

    /** Выключить прокси у клиента (движок продолжает работать). Неблокирующий. */
    fun disable() { wantOn = false; trigger() }

    private fun trigger() {
        rerun = true
        scope.launch {
            if (!mutex.tryLock()) return@launch
            try {
                while (rerun) {
                    rerun = false
                    if (wantOn) {
                        applier.apply(provider.obtain())   // obtain() = Stop+Start движка + конфиг
                    } else {
                        applier.apply(null)
                    }
                }
            } finally {
                mutex.unlock()
            }
        }
    }
}
