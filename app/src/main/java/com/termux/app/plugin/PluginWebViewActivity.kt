package com.termux.app.plugin

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import com.google.gson.Gson
import com.termux.app.compose.KiTerminalTheme
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

class PluginWebViewActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_PLUGIN_ID = "plugin_id"
        private const val EXTRA_ENTRY_PATH = "entry_path"
        private const val EXTRA_TITLE = "title"
    private const val EXTRA_URL = "url"

        fun start(context: Context, pluginId: String, entryPath: String, title: String? = null) {
            val intent = Intent(context, PluginWebViewActivity::class.java).apply {
                putExtra(EXTRA_PLUGIN_ID, pluginId)
                putExtra(EXTRA_ENTRY_PATH, entryPath)
                putExtra(EXTRA_TITLE, title)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    private var pluginId: String = ""
    private var pluginConfig: Map<String, Any> = emptyMap()
    private val gson = Gson()
    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        pluginId = intent.getStringExtra(EXTRA_PLUGIN_ID) ?: run {
            finish()
            return
        }

        val plugin = PluginManager.getPluginById(this, pluginId)
        if (plugin == null || plugin.state != PluginState.ENABLED) {
            finish()
            return
        }

        pluginConfig = PluginManager.getPluginConfig(this, pluginId)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: plugin.manifest.name

        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            KiTerminalTheme {
                PluginWebViewScreen(
                    title = title,
                    pluginId = pluginId,
                    onBack = {
                        if (webView?.canGoBack() == true) {
                            webView?.goBack()
                        } else {
                            finish()
                        }
                    },
                    onWebViewReady = { webView ->
                        this.webView = webView
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
        webView?.resumeTimers()
    }

    override fun onPause() {
        webView?.onPause()
        webView?.pauseTimers()
        super.onPause()
    }

    override fun onDestroy() {
        webView?.stopLoading()
        webView?.clearHistory()
        webView?.clearCache(true)
        webView?.loadUrl("about:blank")
        webView?.removeAllViews()
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (webView?.canGoBack() == true) {
            webView?.goBack()
        } else {
            super.onBackPressed()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    private fun PluginWebViewScreen(
        title: String,
        pluginId: String,
        onBack: () -> Unit,
        onWebViewReady: (WebView) -> Unit
    ) {
        val context = LocalContext.current
        val plugin = PluginManager.getPluginById(context, pluginId)

        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                TopAppBar(
                    title = title,
                    navigationIcon = {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .clickable { onBack() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = MiuixIcons.Back,
                                contentDescription = "返回",
                                tint = MiuixTheme.colorScheme.onSurface,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                )
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            setBackgroundColor(0)
                            setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                allowFileAccess = true
                                allowContentAccess = true
                                allowFileAccessFromFileURLs = true
                                allowUniversalAccessFromFileURLs = true
                                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                mediaPlaybackRequiresUserGesture = false
                                builtInZoomControls = false
                                displayZoomControls = false
                                setSupportZoom(false)
                                cacheMode = WebSettings.LOAD_DEFAULT
                            }

                            addJavascriptInterface(PluginJsBridge(), "TermuxUltra")

                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView,
                                    request: WebResourceRequest
                                ): Boolean {
                                    val url = request.url.toString()
                                    return if (url.startsWith("http://") || url.startsWith("https://")) {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                        startActivity(intent)
                                        true
                                    } else {
                                        false
                                    }
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    view?.injectBackgroundFix()
                                }
                            }

                            webChromeClient = WebChromeClient()

                            val entryPath = intent.getStringExtra(EXTRA_ENTRY_PATH) ?: "web/index.html"
                            val pluginDir = PluginLoader.getPluginDir(ctx, pluginId)
                            val file = java.io.File(pluginDir, entryPath)

                            if (file.exists()) {
                                val baseUrl = "file://${pluginDir.absolutePath}/"
                                loadUrl("$baseUrl$entryPath")
                            } else {
                                val pluginFiles = pluginDir.listFiles()?.joinToString(", ") { it.name } ?: "empty"
                                loadDataWithBaseURL(
                                    "file://${pluginDir.absolutePath}/",
                                    "<html><body style='color:#333;font-family:sans-serif;padding:20px;text-align:center;'>" +
                                        "<h3>插件页面未找到</h3>" +
                                        "<p>缺少文件: $entryPath</p>" +
                                        "<p style='color:#888;font-size:12px;margin-top:16px;'>" +
                                        "插件目录: ${pluginDir.absolutePath}</p>" +
                                        "<p style='color:#888;font-size:12px;'>" +
                                        "目录内容: $pluginFiles</p>" +
                                        "<p style='color:#aaa;font-size:11px;margin-top:20px;'>" +
                                        "请重新安装插件或检查插件包是否完整</p>" +
                                        "</body></html>",
                                    "text/html",
                                    "UTF-8",
                                    null
                                )
                            }

                            onWebViewReady(this)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    inner class PluginJsBridge {

        @JavascriptInterface
        fun getPluginInfo(): String {
            val plugin = PluginManager.getPluginById(this@PluginWebViewActivity, pluginId)
                ?: return gson.toJson(mapOf("error" to "Plugin not found"))

            return gson.toJson(mapOf(
                "id" to plugin.id,
                "name" to plugin.manifest.name,
                "version" to plugin.manifest.version,
                "enabled" to (plugin.state == PluginState.ENABLED),
                "permissions" to plugin.grantedPermissions.map { it.name }
            ))
        }

        @JavascriptInterface
        fun getConfig(): String {
            return gson.toJson(pluginConfig)
        }

        @JavascriptInterface
        fun setConfig(key: String, value: String): Boolean {
            return try {
                val config = pluginConfig.toMutableMap()
                config[key] = value
                PluginManager.savePluginConfig(this@PluginWebViewActivity, pluginId, config)
                pluginConfig = config
                true
            } catch (_: Exception) {
                false
            }
        }

        @JavascriptInterface
        fun exec(command: String): String {
            val result = PluginManager.executeShellCommand(
                this@PluginWebViewActivity,
                pluginId,
                command
            )
            return if (result.isSuccess) {
                gson.toJson(mapOf("success" to true, "output" to result.getOrDefault("")))
            } else {
                gson.toJson(mapOf("success" to false, "error" to result.exceptionOrNull()?.message))
            }
        }

        @JavascriptInterface
        fun readFile(path: String): String {
            val content = PluginManager.getPluginFileContent(this@PluginWebViewActivity, pluginId, path)
            return if (content != null) {
                gson.toJson(mapOf("success" to true, "content" to content))
            } else {
                gson.toJson(mapOf("success" to false, "error" to "File not found or permission denied"))
            }
        }

        @JavascriptInterface
        fun openUrl(url: String) {
            PluginManager.openUrl(this@PluginWebViewActivity, url)
        }

        @JavascriptInterface
        fun toast(message: String) {
            runOnUiThread {
                android.widget.Toast.makeText(
                    this@PluginWebViewActivity,
                    message,
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }

        @JavascriptInterface
        fun finishPage() {
            runOnUiThread {
                this@PluginWebViewActivity.finish()
            }
        }

        @JavascriptInterface
        fun getDeviceInfo(): String {
            val info = mapOf(
                "model" to android.os.Build.MODEL,
                "brand" to android.os.Build.BRAND,
                "androidVersion" to android.os.Build.VERSION.RELEASE,
                "sdkVersion" to android.os.Build.VERSION.SDK_INT,
                "termuxVersion" to "1.2.0",
                "rootAvailable" to PluginSecurity.checkRootAvailability()
            )
            return gson.toJson(info)
        }
    }

    private fun WebView.injectBackgroundFix() {
        val js = """
            (function() {
                var html = document.documentElement;
                var body = document.body;
                if (html) { html.style.margin = '0'; html.style.padding = '0'; html.style.minHeight = '100%'; }
                if (body) { body.style.margin = '0'; body.style.padding = '0'; body.style.minHeight = '100vh'; }
                var target = body || html;
                if (!target) return;
                var computed = window.getComputedStyle(target);
                var bgImage = computed.backgroundImage;
                var bgColor = computed.backgroundColor;
                var bg = '';
                if (bgImage && bgImage !== 'none') {
                    bg = bgImage;
                } else if (bgColor && bgColor !== 'rgba(0, 0, 0, 0)' && bgColor !== 'transparent') {
                    bg = bgColor;
                }
                if (bg) {
                    html.style.setProperty('background', bg, 'important');
                }
            })();
        """.trimIndent()
        evaluateJavascript(js, null)
    }
}