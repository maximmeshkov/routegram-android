package com.routegram.glue

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Проба «есть ли НОРМАЛЬНЫЙ (иностранный) интернет» — то самое условие, при котором наш
 * прокси имеет смысл. Это НЕ системная валидация: мы сами ходим в пул иностранных
 * `generate_204`-эндпоинтов (отвечают пустым 204 — без ложняка от captive-portal/DPI-редиректов).
 *
 * «Есть» = ответил ЛЮБОЙ из пула. При белом списке/офлайне все недоступны → false.
 *
 * Пул пока константа; позже вынесем в обновляемый конфиг (вместе с прокси-доменами).
 */
object ForeignProbe {

    // Иностранные хосты: доступны при нормальном инете, режутся при белых списках.
    private val ENDPOINTS = listOf(
        "https://www.google.com/generate_204",
        "https://connectivitycheck.gstatic.com/generate_204"
    )
    private const val TIMEOUT_MS = 3000

    suspend fun hasNormalInternet(): Boolean = withContext(Dispatchers.IO) {
        ENDPOINTS.any { reachable(it) }
    }

    private fun reachable(url: String): Boolean = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = false
            requestMethod = "GET"
        }
        val ok = c.responseCode == 204   // строго 204 — без ложняка от редиректов
        c.disconnect()
        ok
    } catch (t: Throwable) {
        false
    }
}
