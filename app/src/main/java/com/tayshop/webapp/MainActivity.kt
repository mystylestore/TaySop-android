package com.tayshop.webapp

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

class MainActivity : AppCompatActivity() {

    companion object {
        private const val START_URL = "https://script.google.com/macros/s/AKfycbxWuafFw3IW-BVfdSarB1eA_hMkuW02KrH4iSblIVTM1nsixzh_gOMSwfLKtgrduWQ_/exec"
    }

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var offlineLayout: LinearLayout
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = fileUploadCallback ?: return@registerForActivityResult
        val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        callback.onReceiveValue(uris)
        fileUploadCallback = null
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Permisos procesados para funciones de archivos o cámara
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        val rootLayout: View = findViewById(R.id.rootLayout)
        val statusBarScrim: View = findViewById(R.id.statusBarScrim)
        val navBarScrim: View = findViewById(R.id.navBarScrim)

        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = false
        insetsController.isAppearanceLightNavigationBars = true
        

        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, windowInsets ->
            val sysInsets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val cutoutInsets = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val imeInsets = windowInsets.getInsets(WindowInsetsCompat.Type.ime())

            val topHeight = sysInsets.top
            val bottomHeight = maxOf(sysInsets.bottom, imeInsets.bottom)

            if (statusBarScrim.layoutParams.height != topHeight) {
                statusBarScrim.layoutParams = statusBarScrim.layoutParams.apply {
                    height = topHeight
                }
                statusBarScrim.requestLayout()
            }

            if (navBarScrim.layoutParams.height != bottomHeight) {
                navBarScrim.layoutParams = navBarScrim.layoutParams.apply {
                    height = bottomHeight
                }
                navBarScrim.requestLayout()
            }

            view.setPadding(sysInsets.left, 0, sysInsets.right, 0)
            WindowInsetsCompat.CONSUMED
        }

        webView = findViewById(R.id.webView)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        progressBar = findViewById(R.id.progressBar)
        offlineLayout = findViewById(R.id.offlineLayout)
        val retryButton: Button = findViewById(R.id.retryButton)

        requestOptionalPermissions()
        configureWebView()
        configureSwipeRefresh()

        // Limpiar almacenamiento local al iniciar para mostrar siempre el login inicial
        WebStorage.getInstance().deleteAllData()
        webView.clearCache(true)

        retryButton.setOnClickListener {
            if (hasNetworkConnection()) {
                offlineLayout.visibility = View.GONE
                webView.visibility = View.VISIBLE
                webView.loadUrl(webView.url ?: START_URL)
            } else {
                Toast.makeText(this, "Sin conexión a internet", Toast.LENGTH_SHORT).show()
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })

        val initialUri = intent?.data?.toString()
        val urlToLoad = if (!initialUri.isNullOrBlank() && initialUri.startsWith("https://")) {
            initialUri
        } else {
            START_URL
        }

        if (hasNetworkConnection()) {
            webView.loadUrl(urlToLoad)
        } else {
            showOfflineState()
        }
    }

    private fun requestOptionalPermissions() {
        val needed = mutableListOf<String>()
        // Permiso de cámara desactivado en configuración
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            loadsImagesAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            textZoom = 100
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            // User-Agent Chrome móvil estándar para compatibilidad total con Google Apps Script y OAuth
            userAgentString = userAgentString
                .replace("; wv", "")
                .replace("Version/4.0 ", "")
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                val scheme = uri.scheme?.lowercase() ?: ""
                val host = uri.host?.lowercase() ?: ""

                if (scheme == "http" || scheme == "https") {
                    val isInternalGoogleHost = host.endsWith("script.google.com") ||
                        host.endsWith("googleusercontent.com") ||
                        host.endsWith("accounts.google.com") ||
                        host.endsWith("accounts.youtube.com") ||
                        host.endsWith("drive.google.com") ||
                        host.endsWith("docs.google.com") ||
                        host.endsWith("gstatic.com") ||
                        host.endsWith("googleapis.com") ||
                        host == Uri.parse(START_URL).host?.lowercase()

                    if (isInternalGoogleHost) {
                        return false
                    }
                    // Abrir enlaces externos (ej. WhatsApp, enlaces fuera de Google) en su app correspondiente
                    return try {
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                        true
                    } catch (_: Exception) {
                        false
                    }
                }

                // Esquemas nativos: tel:, mailto:, whatsapp:, intent:
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                    true
                } catch (_: Exception) {
                    true
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                offlineLayout.visibility = View.GONE
                webView.visibility = View.VISIBLE
                progressBar.visibility = View.VISIBLE
                hideAppsScriptBanner(view)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
                swipeRefresh.isRefreshing = false
                CookieManager.getInstance().flush()
                hideAppsScriptBanner(view)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    showOfflineState()
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
                if (newProgress >= 100) {
                    progressBar.visibility = View.GONE
                }
                if (newProgress >= 25) {
                    hideAppsScriptBanner(view)
                }
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.fileUploadCallback?.onReceiveValue(null)
                this@MainActivity.fileUploadCallback = filePathCallback
                return try {
                    val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }
                    fileChooserLauncher.launch(intent)
                    true
                } catch (_: Exception) {
                    this@MainActivity.fileUploadCallback = null
                    Toast.makeText(this@MainActivity, "No se pudo abrir el selector de archivos", Toast.LENGTH_SHORT).show()
                    false
                }
            }
        }

        webView.setDownloadListener { downloadUrl, userAgent, contentDisposition, mimeType, _ ->
            try {
                val request = DownloadManager.Request(Uri.parse(downloadUrl)).apply {
                    setMimeType(mimeType)
                    val cookies = CookieManager.getInstance().getCookie(downloadUrl)
                    if (!cookies.isNullOrEmpty()) addRequestHeader("Cookie", cookies)
                    addRequestHeader("User-Agent", userAgent)
                    val filename = URLUtil.guessFileName(downloadUrl, contentDisposition, mimeType)
                    setTitle(filename)
                    setDescription("Descargando archivo...")
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
                }
                val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                manager.enqueue(request)
                Toast.makeText(this, "Descarga iniciada...", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)))
                } catch (_: Exception) {
                    Toast.makeText(this, "No se pudo descargar el archivo", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun configureSwipeRefresh() {
        swipeRefresh.isEnabled = true
        swipeRefresh.setColorSchemeColors(ContextCompat.getColor(this, R.color.brand_primary))
        swipeRefresh.setOnChildScrollUpCallback { _, _ ->
            webView.canScrollVertically(-1)
        }
        swipeRefresh.setOnRefreshListener {
            if (hasNetworkConnection()) {
                webView.reload()
            } else {
                swipeRefresh.isRefreshing = false
                showOfflineState()
            }
        }
    }

    private fun showOfflineState() {
        progressBar.visibility = View.GONE
        swipeRefresh.isRefreshing = false
        webView.visibility = View.GONE
        offlineLayout.visibility = View.VISIBLE
    }

    private fun hideAppsScriptBanner(view: WebView?) {
        val js = "(function(){var id='hide-gas-banner';if(!document.getElementById(id)){var s=document.createElement('style');s.id=id;s.textContent='#warning,.warning-bar,#warning-bar-table>tbody>tr:first-child{display:none!important;height:0!important;visibility:hidden!important;}html,body,#warning-bar-table,#sandboxFrame{height:100%!important;margin:0!important;padding:0!important;}';(document.head||document.documentElement).appendChild(s);}var w=document.getElementById('warning');if(w){var tr=w.closest('tr');if(tr)tr.style.display='none';w.style.display='none';}})();"
        view?.evaluateJavascript(js, null)
    }

    private fun hasNetworkConnection(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
