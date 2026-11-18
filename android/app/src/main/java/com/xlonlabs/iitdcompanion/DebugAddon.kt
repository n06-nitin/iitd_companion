package com.xlonlabs.iitdcompanion

import android.webkit.WebView
import org.json.JSONObject

/**
 * Hook for personal extras in local debug builds. The implementation lives
 * outside git and only a local Gradle script (debug-addon.gradle) compiles it
 * into the debug variant, so release builds and fresh clones have none and
 * every call here is a no-op.
 */
interface DebugAddon {
    /** Every message a tab's injected script posts to the app, on the main thread. */
    fun onMessage(webView: WebView, msg: JSONObject)

    companion object {
        private val instance: DebugAddon? by lazy {
            runCatching {
                Class.forName("com.xlonlabs.iitdcompanion.addon.Addon")
                    .getDeclaredConstructor().newInstance() as DebugAddon
            }.getOrNull()
        }

        /** An add-on can't take the app down with it. */
        fun onMessage(webView: WebView, msg: JSONObject) {
            runCatching { instance?.onMessage(webView, msg) }
        }
    }
}
