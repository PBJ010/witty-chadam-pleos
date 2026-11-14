package com.journeymanager.pleos

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * The shell: one WebView showing the web app, with the car bridge (window.pleos, see pleos-bridge.js and the web app's
 * src/pleos.ts) planted before the page's own scripts run. Everything the traveller sees is the web app.
 */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var native: PleosNative

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)  // chrome://inspect / adb forward, debug builds only
        setContentView(web)
        native = PleosNative(this) { script -> runOnUiThread { web.evaluateJavascript(script, null) } }
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            userAgentString = "$userAgentString JourneyManagerPleos/${BuildConfig.VERSION_NAME}"
        }
        if (BuildConfig.SOFTWARE_WEBVIEW) web.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        web.addJavascriptInterface(native, "PleosNative")
        val shim = assets.open("pleos-bridge.js").bufferedReader().readText()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, shim, setOf("*"))
        }
        web.webViewClient = object : WebViewClient() {
            // Also at page start, for a WebView without DOCUMENT_START_SCRIPT (before the app's module scripts arrive).
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) { view.evaluateJavascript(shim, null) }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false
        }
        native.start()
        // FusedLocation's permission and the microphone are dangerous ones (checked on the emulator, 2026-11-02): ask once.
        val ask = arrayOf(FUSED_LOCATION, Manifest.permission.RECORD_AUDIO).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (ask.isNotEmpty()) requestPermissions(ask.toTypedArray(), 1)
        web.loadUrl(BuildConfig.APP_URL)
    }

    private companion object { const val FUSED_LOCATION = "pleos.car.permission.FUSED_LOCATION" }

    override fun onDestroy() { native.stop(); web.destroy(); super.onDestroy() }
}
