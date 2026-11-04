package com.routegram.wsproxy

import android.content.Context
import android.util.Log
import com.routegram.core.ProxyConfig
import com.routegram.core.ProxyProvider

/** Local MTProto provider backed by the unchanged Flowseal upstream package. */
class WsProxyProvider(
    private val context: Context,
    private val settings: WsProxySettings = WsProxySettings()
) : ProxyProvider {
    override suspend fun obtain(): ProxyConfig? = try {
        WsProxyNative.start(context, settings)
    } catch (error: Exception) {
        Log.e("Routegram", "Flowseal startup failed", error)
        null
    } catch (error: LinkageError) {
        Log.e("Routegram", "Flowseal runtime unavailable", error)
        null
    }
}
