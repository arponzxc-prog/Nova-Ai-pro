/*
 * © ARPON — original NOVA AI code. Hosts the HUD orb asset derived from JARVIS (see assets/nova_hud/orb.html header and
 * THIRD_PARTY_NOTICES.md: JARVIS AI by Tech GPTx, TAMCL v1.0).
 *
 * WebView hardening: local assets only (everything else is blocked in shouldInterceptRequest), no network loads, no file/content
 * access, no mixed content, NO addJavascriptInterface (state is pushed with evaluateJavascript using fixed strings
 * from enums, never user text), remote debugging off, destroyed on dispose, paused when the app is not resumed.
 */
package com.example.ui.hud

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.animation.core.*
import com.example.ui.theme.LocalNovaTokens
import com.example.ui.theme.NovaThemeId

private const val ORB_URL = "file:///android_asset/nova_hud/orb.html"
private const val ORB_ASSET_PREFIX = "file:///android_asset/nova_hud/"

/** Reads device conditions that make the 3D orb a bad idea. Cheap; call from composition. */
internal fun orbRendererFor(context: Context): OrbRenderer {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    val animOff = runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }.getOrDefault(false)
    return OrbRendererPolicy.choose(
        isLowRamDevice = am?.isLowRamDevice == true,
        batterySaver = pm?.isPowerSaveMode == true,
        animationsDisabled = animOff,
        webViewUsable = true
    )
}

/**
 * The NOVA orb. [level] is the audio level 0..1 (user RMS while listening, playback RMS while speaking).
 * Falls back to a Canvas orb on low-end/battery-saver/reduce-motion devices or if the WebView fails to load.
 */
@Composable
fun NovaOrb(state: OrbState, level: Float, modifier: Modifier = Modifier, description: String = "") {
    val context = LocalContext.current
    var webFailed by remember { mutableStateOf(false) }
    val renderer = remember(webFailed) { if (webFailed) OrbRenderer.CANVAS else orbRendererFor(context) }
    Box(modifier.semantics { if (description.isNotEmpty()) contentDescription = description }) {
        if (renderer == OrbRenderer.WEBVIEW_3D) WebOrb(state, level, onFailed = { webFailed = true }) else CanvasOrb(state, level)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebOrb(state: OrbState, level: Float, onFailed: () -> Unit) {
    val tokens = LocalNovaTokens.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var view by remember { mutableStateOf<WebView?>(null) }
    var pageReady by remember { mutableStateOf(false) }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val wv = try { WebView(ctx) } catch (e: Throwable) { onFailed(); return@AndroidView android.view.View(ctx) }
            wv.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            wv.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            wv.importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO // description lives on the Compose wrapper
            wv.settings.apply {
                javaScriptEnabled = true            // required by the local orb script only
                allowFileAccess = false             // file:///android_asset stays reachable; the file system does not
                allowContentAccess = false
                @Suppress("DEPRECATION") allowFileAccessFromFileURLs = false
                @Suppress("DEPRECATION") allowUniversalAccessFromFileURLs = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                blockNetworkLoads = true
                setGeolocationEnabled(false)
                domStorageEnabled = false
                cacheMode = WebSettings.LOAD_NO_CACHE
                setSupportZoom(false)
            }
            WebView.setWebContentsDebuggingEnabled(false)
            wv.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) = true // never navigate away
                override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? =
                    if (r.url.toString().startsWith(ORB_ASSET_PREFIX)) null
                    else WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))
                override fun onPageFinished(v: WebView, url: String?) { pageReady = true }
                override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) { if (r.isForMainFrame) onFailed() }
                override fun onRenderProcessGone(v: WebView, d: android.webkit.RenderProcessGoneDetail): Boolean { onFailed(); return true }
            }
            wv.loadUrl(ORB_URL)
            view = wv
            wv
        },
        onRelease = { wv ->
            runCatching {
                (wv.parent as? ViewGroup)?.removeView(wv)
                (wv as? WebView)?.apply { stopLoading(); loadUrl("about:blank"); destroy() }
            }
            view = null
        }
    )

    // Pause rendering whenever the app is not resumed (background, screen off).
    DisposableEffect(lifecycleOwner, view, pageReady) {
        val observer = LifecycleEventObserver { _, e ->
            val wv = view ?: return@LifecycleEventObserver
            when (e) {
                Lifecycle.Event.ON_RESUME -> { wv.onResume(); if (pageReady) wv.evaluateJavascript("NovaOrbControl.resume()", null) }
                Lifecycle.Event.ON_PAUSE -> { if (pageReady) wv.evaluateJavascript("NovaOrbControl.pause()", null); wv.onPause() }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Theme follows the app theme.
    LaunchedEffect(view, pageReady, tokens.id) {
        if (pageReady) view?.evaluateJavascript("NovaOrb.setTheme('${tokens.id.key}')", null)
    }
    // State (fixed enum strings only).
    LaunchedEffect(view, pageReady, state) {
        if (pageReady) view?.evaluateJavascript("NovaOrb.setState('${state.js}')", null)
    }
    // Audio level: coarse steps (≈ 12 fps of updates at most) so there is no recomposition/JS storm.
    val step = (level.coerceIn(0f, 1f) * 20f).toInt()
    LaunchedEffect(view, pageReady, step) {
        if (pageReady) {
            val l = step / 20f
            view?.evaluateJavascript("NovaOrb.setExternalAudio(${"%.2f".format(java.util.Locale.US, l)},${"%.2f".format(java.util.Locale.US, l * 0.7f)},${"%.2f".format(java.util.Locale.US, l * 0.5f)})", null)
        }
    }
}

/** Lightweight fallback: layered radial gradients pulsing per state. No WebView, no WebGL. */
@Composable
fun CanvasOrb(state: OrbState, level: Float, modifier: Modifier = Modifier.fillMaxSize()) {
    val t = LocalNovaTokens.current
    val animationsOff = remember { false }
    val transition = rememberInfiniteTransition(label = "orb")
    val speed = when (state) { OrbState.IDLE -> 3200; OrbState.LISTENING -> 1500; OrbState.THINKING -> 900; OrbState.SPEAKING -> 1100 }
    val pulse by transition.animateFloat(0.94f, 1.06f, infiniteRepeatable(tween(speed, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    val core = when (state) { OrbState.IDLE -> t.accent1; OrbState.LISTENING -> t.accent3; OrbState.THINKING -> t.accent2; OrbState.SPEAKING -> t.accent1 }
    Canvas(modifier) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val base = minOf(size.width, size.height) * 0.30f
        val r = base * (if (animationsOff) 1f else pulse) * (1f + level.coerceIn(0f, 1f) * 0.18f)
        drawCircle(Brush.radialGradient(listOf(core.copy(alpha = 0.35f), Color.Transparent), c, r * 2.3f), r * 2.3f, c)
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.85f), core, core.copy(alpha = 0.15f)), c, r), r, c)
    }
}
