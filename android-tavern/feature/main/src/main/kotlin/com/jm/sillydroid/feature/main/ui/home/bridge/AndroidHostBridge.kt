package com.jm.sillydroid.feature.main.ui.home.bridge

import android.webkit.JavascriptInterface

/**
 * SillyDroid 页面与 Android 宿主之间的只读/动作桥。
 *
 * 允许暴露经过宿主校验的页面动作和系统状态快照；不允许在桥内持有 Activity View、
 * 直接执行页面业务或返回未裁剪的设备敏感标识。
 */
class AndroidHostBridge(
    private val isHostActive: () -> Boolean,
    private val runOnUiThread: (() -> Unit) -> Unit,
    private val openSettings: () -> Unit,
    private val showFloatingLogsBubble: () -> Unit,
    private val requestOpenCurrentPageInBrowser: () -> Unit,
    // 这里必须和 @JavascriptInterface 方法名区分开，避免 Kotlin 在 lambda 里解析成当前桥接方法并递归调用。
    private val applyFloatingLogsBubbleEnabled: (Boolean) -> Unit,
    private val applyWebViewPullRefreshEnabled: (Boolean) -> Unit,
    private val applySystemBarsBackgroundColor: (String) -> Unit,
    private val applySystemBarsBackgroundColors: (String, String) -> Unit,
    private val reloadTavern: () -> Unit,
    private val hostVersionInfoJson: () -> String,
    private val systemInfoJson: () -> String = { "{}" },
    private val recordWebPerformanceDiagnosticPayload: (String) -> Unit = {}
) {
    private companion object {
        private const val WEB_PERFORMANCE_DIAGNOSTIC_PAYLOAD_LIMIT = 4_096
    }

    @JavascriptInterface
    fun openSettings(): Boolean {
        if (!isHostActive()) {
            return false
        }

        runOnUiThread(openSettings)
        return true
    }

    @JavascriptInterface
    fun showFloatingLogsBubble(): Boolean {
        if (!isHostActive()) {
            return false
        }

        runOnUiThread(showFloatingLogsBubble)
        return true
    }

    @JavascriptInterface
    fun openCurrentPageInBrowser(): Boolean {
        if (!isHostActive()) {
            return false
        }

        runOnUiThread(requestOpenCurrentPageInBrowser)
        return true
    }

    @JavascriptInterface
    fun setFloatingLogsBubbleEnabled(enabled: Boolean): Boolean {
        if (!isHostActive()) {
            return false
        }

        runOnUiThread {
            applyFloatingLogsBubbleEnabled(enabled)
        }
        return true
    }

    @JavascriptInterface
    fun setWebViewPullRefreshEnabled(enabled: Boolean): Boolean {
        if (!isHostActive()) {
            return false
        }

        runOnUiThread {
            applyWebViewPullRefreshEnabled(enabled)
        }
        return true
    }

    @JavascriptInterface
    fun setSystemBarsBackgroundColor(hexColor: String): Boolean {
        if (!isHostActive() || hexColor.isBlank()) {
            return false
        }

        runOnUiThread {
            applySystemBarsBackgroundColor(hexColor)
        }
        return true
    }

    @JavascriptInterface
    fun setSystemBarsBackgroundColors(statusBarHexColor: String, navigationBarHexColor: String): Boolean {
        if (!isHostActive() || statusBarHexColor.isBlank() || navigationBarHexColor.isBlank()) {
            return false
        }

        runOnUiThread {
            applySystemBarsBackgroundColors(statusBarHexColor, navigationBarHexColor)
        }
        return true
    }

    @JavascriptInterface
    fun reloadTavern(): Boolean {
        if (!isHostActive()) {
            return false
        }

        runOnUiThread(reloadTavern)
        return true
    }

    @JavascriptInterface
    fun getHostVersionInfo(): String {
        return hostVersionInfoJson()
    }

    /** 返回一次性 JSON 系统状态快照；采集失败字段由宿主采集器标记为不可用。 */
    @JavascriptInterface
    fun getSystemInfo(): String {
        return systemInfoJson()
    }

    @JavascriptInterface
    fun recordWebPerformanceDiagnostic(payload: String): Boolean {
        if (!isHostActive() || payload.isBlank()) {
            return false
        }

        // 性能探针只需要一行聚合摘要；这里裁剪长度并移除换行，避免页面侧误传超长内容污染宿主诊断日志。
        val compactPayload = payload
            .replace("\r", " ")
            .replace("\n", " ")
            .trim()
            .take(WEB_PERFORMANCE_DIAGNOSTIC_PAYLOAD_LIMIT)
        if (compactPayload.isBlank()) {
            return false
        }
        recordWebPerformanceDiagnosticPayload(compactPayload)
        return true
    }
}
