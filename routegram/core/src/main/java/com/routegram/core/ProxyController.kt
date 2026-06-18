package com.routegram.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * Оркестратор обхода: единственное место, где встречаются
 * "источник прокси" ([ProxyProvider]) и "клиент" ([ProxyApplier]).
 *
 * На шаге 1 это каркас — спрашивает у провайдера конфигурацию и отдаёт её
 * в applier. Реальная логика (повторы, ротация, авто-выбор лучшего узла,
 * реакция на разрыв связи) появится на следующих шагах.
 */
class ProxyController(
    private val provider: ProxyProvider,
    private val applier: ProxyApplier
) {
    // Своя корутинная область (≈ собственный пул задач). IO-диспетчер — для сетевых
    // операций. SupervisorJob: падение одной задачи не валит остальные.
    // ВАЖНО: scope НЕ торчит в публичном конструкторе — иначе потребителям (:routegram:glue)
    // пришлось бы тащить kotlinx-coroutines на classpath. Держим его внутренним.
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Сериализация + коалесинг: одновременные триггеры не стакаются, но финальный
    // проход после последнего триггера гарантирован (флаг rerun).
    private val mutex = Mutex()
    @Volatile private var rerun = false

    /** Первичный запуск: запросить прокси и применить к клиенту. Неблокирующий. */
    fun start() = trigger()

    /**
     * Перезапросить прокси и применить заново — после смены сети или залипания.
     * Провайдер при повторном obtain() делает чистый рестарт движка.
     */
    fun restart() = trigger()

    private fun trigger() {
        rerun = true
        scope.launch {
            // Уже крутится цикл — он подхватит свежий rerun; второй запускать не нужно.
            if (!mutex.tryLock()) return@launch
            try {
                while (rerun) {
                    rerun = false
                    val config = provider.obtain()
                    applier.apply(config)
                }
            } finally {
                mutex.unlock()
            }
        }
    }
}
