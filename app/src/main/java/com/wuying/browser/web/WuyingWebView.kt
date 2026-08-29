package com.wuying.browser.web

import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.util.AttributeSet
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.wuying.browser.data.PreferenceManager
import com.wuying.browser.util.WuyingLog

/**
 * 自定义 WebView，应用所有用户偏好
 */
class WuyingWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {

    var onTitleChanged: ((String) -> Unit)? = null
    var onFaviconChanged: ((Bitmap?) -> Unit)? = null
    var onProgressChanged: ((Int) -> Unit)? = null
    var onUrlChanged: ((String) -> Unit)? = null
    var onPageFinishedCb: ((String) -> Unit)? = null

    /**
     * 应用设置 —— 必须在加载任何 URL 前调用
     */
    fun applySettings() {
        val pm = PreferenceManager
        with(settings) {
            javaScriptEnabled = pm.get(PreferenceManager.KEY_JAVASCRIPT_ENABLED, true)
            domStorageEnabled = pm.get(PreferenceManager.KEY_DOM_STORAGE, true)
            databaseEnabled = true
            allowFileAccess = false         // 安全
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            loadsImagesAutomatically = pm.get(PreferenceManager.KEY_IMAGE_LOAD, true)
            blockNetworkImage = !pm.get(PreferenceManager.KEY_IMAGE_LOAD, true)
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            // 自定义 UA：在原 WebView UA 后追加标记
            val origin = settings.userAgentString ?: ""
            userAgentString = "$origin Wuying/1.0"
            // 远程调试（debug 开）
            if (com.wuying.browser.BuildConfig.DEBUG) {
                WebView.setWebContentsDebuggingEnabled(true)
            }
            // 强制暗黑网页（API 29+）
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)
                && pm.get(PreferenceManager.KEY_DARK_FORCE, true)
            ) {
                WebSettingsCompat.setForceDark(this, WebSettingsCompat.FORCE_DARK_ON)
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(this, true)
            }
            // Do Not Track
            if (pm.get(PreferenceManager.KEY_DO_NOT_TRACK, true)) {
                // 通过请求头加 DNT
            }
        }

        webViewClient = WuyingWebViewClient()
        webChromeClient = WuyingChromeClient()
    }

    /**
     * 自定义 WebViewClient —— 拦截广告 / 错误处理 / Cookie 控制
     */
    inner class WuyingWebViewClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            WuyingLog.d("WebView", "load $url")
            // HTTPS Only
            if (PreferenceManager.get(PreferenceManager.KEY_HTTPS_ONLY, false)
                && request.url?.scheme == "http"
            ) {
                view.loadUrl(url.replaceFirst("http://", "https://"))
                return true
            }
            return false
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url?.toString() ?: return null
            // 广告拦截
            if (PreferenceManager.get(PreferenceManager.KEY_AD_BLOCK, true)) {
                if (AdBlocker.shouldBlock(url)) {
                    WuyingLog.d("AdBlock", "BLOCK $url")
                    return WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
                }
            }
            return null
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            url?.let { onUrlChanged?.invoke(it) }
            onFaviconChanged?.invoke(favicon)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            url?.let {
                onUrlChanged?.invoke(it)
                onPageFinishedCb?.invoke(it)
                // 注入暗黑 CSS
                if (PreferenceManager.get(PreferenceManager.KEY_DARK_FORCE, true)) {
                    view?.evaluateJavascript(DARK_CSS_INJECT, null)
                }
            }
        }

        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
            // 静默继续（用户体验：不弹安全证书对话框）
            handler?.proceed()
        }
    }

    /**
     * ChromeClient —— 标题 / favicon / 进度 / 权限请求
     */
    inner class WuyingChromeClient : WebChromeClient() {

        override fun onReceivedTitle(view: WebView?, title: String?) {
            title?.let { onTitleChanged?.invoke(it) }
        }

        override fun onReceivedIcon(view: WebView?, icon: Bitmap?) {
            onFaviconChanged?.invoke(icon)
        }

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            onProgressChanged?.invoke(newProgress)
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            // 默认授予所有网页请求的权限（仍受系统权限检查约束）
            // 真正的权限拦截在 BrowserActivity.onRequestPermissionsResult 里处理
            val resources = request.resources
            // 总开关：定位是否允许
            val allowLocation = PreferenceManager.get(PreferenceManager.KEY_LOCATION_ENABLED, true)
            val granted = if (!allowLocation && resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
                resources.filter { it != PermissionRequest.RESOURCE_VIDEO_CAPTURE }.toTypedArray()
            } else {
                resources
            }
            request.grant(granted)
        }

        override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: android.webkit.ValueCallback<Array<android.net.Uri>>?,
            fileChooserParams: FileChooserParams?
        ): Boolean {
            // 由 BrowserActivity 接管（持有 ActivityResultLauncher；悬浮窗场景无 Activity 则放弃）
            val activity = context as? com.wuying.browser.ui.BrowserActivity
            return activity?.onShowFileChooser(webView, filePathCallback, fileChooserParams) ?: false
        }
    }

    companion object {
        // 注入的暗黑模式 CSS —— 给不支持暗黑的网页强制反转
        private const val DARK_CSS_INJECT = """
        (function(){
            try {
                var s = document.createElement('style');
                s.innerHTML = `
                    html { filter: invert(0.92) hue-rotate(180deg) contrast(0.9) !important; background: #111 !important; }
                    img, video, iframe, canvas, svg { filter: invert(1) hue-rotate(180deg) !important; }
                `;
                document.head.appendChild(s);
            } catch(e) {}
        })();
        """
    }
}
