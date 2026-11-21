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
import android.provider.MediaStore
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
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
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        private const val START_URL = "https://script.google.com/macros/s/AKfycbxWuafFw3IW-BVfdSarB1eA_hMkuW02KrH4iSblIVTM1nsixzh_gOMSwfLKtgrduWQ_/exec"
        private const val SESSION_TIMEOUT_MS = 1800000L
    }

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var offlineLayout: LinearLayout
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var cameraPhotoUri: Uri? = null
    private var pendingCaptureAfterPermission = false
    private var hasInitializedWebView = false
    private var lastTouchRecordedAt = 0L

    private val sessionPrefs by lazy {
        getSharedPreferences("app_session_prefs", Context.MODE_PRIVATE)
    }

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = fileUploadCallback ?: return@registerForActivityResult
        var uris: Array<Uri>? = null
        if (result.resultCode == RESULT_OK) {
            val parsed = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            if (parsed != null && parsed.isNotEmpty()) {
                uris = parsed
            } else if (cameraPhotoUri != null) {
                uris = arrayOf(cameraPhotoUri!!)
            }
        }
        callback.onReceiveValue(uris)
        fileUploadCallback = null
        cameraPhotoUri = null
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (pendingCaptureAfterPermission) {
            pendingCaptureAfterPermission = false
            val granted = results[Manifest.permission.CAMERA] == true ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                launchCameraCapture()
            } else {
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = null
                Toast.makeText(this, "Activa el permiso de cámara para tomar fotos", Toast.LENGTH_SHORT).show()
            }
        }
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

        if (isSessionExpired()) {
            WebStorage.getInstance().deleteAllData()
            webView.clearCache(true)
            markActiveNow(force = true)
            if (hasNetworkConnection()) {
                webView.loadUrl(urlToLoad)
            } else {
                showOfflineState()
            }
        } else {
            markActiveNow(force = true)
            if (savedInstanceState != null && webView.restoreState(savedInstanceState) != null) {
                // Estado y pantalla restaurados exactamente donde quedó el usuario
            } else if (hasNetworkConnection()) {
                webView.loadUrl(urlToLoad)
            } else {
                showOfflineState()
            }
        }
        hasInitializedWebView = true
    }

    override fun onStart() {
        super.onStart()
        if (!hasInitializedWebView) return
        if (isSessionExpired()) {
            WebStorage.getInstance().deleteAllData()
            webView.evaluateJavascript(
                "try{localStorage.removeItem('ts_token');localStorage.removeItem('ts_user');}catch(e){}",
                null
            )
            markActiveNow(force = true)
            if (hasNetworkConnection()) {
                webView.loadUrl(START_URL)
            } else {
                showOfflineState()
            }
        } else {
            markActiveNow(force = true)
        }
    }

    override fun onStop() {
        super.onStop()
        markActiveNow(force = true)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        markActiveNow(force = false)
    }

    private fun isSessionExpired(): Boolean {
        if (SESSION_TIMEOUT_MS == 0L) return true
        if (SESSION_TIMEOUT_MS < 0L) return false
        val lastActive = sessionPrefs.getLong("last_active_at", 0L)
        if (lastActive == 0L) return true
        return (System.currentTimeMillis() - lastActive) >= SESSION_TIMEOUT_MS
    }

    private fun markActiveNow(force: Boolean) {
        val now = System.currentTimeMillis()
        if (force || now - lastTouchRecordedAt >= 10_000L) {
            lastTouchRecordedAt = now
            sessionPrefs.edit().putLong("last_active_at", now).apply()
        }
    }

    private fun requestOptionalPermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.CAMERA)
        }
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

            override fun onPermissionRequest(request: PermissionRequest?) {
                request ?: return
                runOnUiThread {
                    request.grant(request.resources)
                }
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.fileUploadCallback?.onReceiveValue(null)
                this@MainActivity.fileUploadCallback = filePathCallback

                val wantsCapture = fileChooserParams?.isCaptureEnabled == true
                if (wantsCapture) {
                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                        pendingCaptureAfterPermission = true
                        permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
                        return true
                    }
                    return launchCameraCapture()
                }

                return try {
                    val acceptTypes = fileChooserParams?.acceptTypes?.joinToString(",") ?: ""
                    val mimeType = if (acceptTypes.contains("image")) "image/*" else "*/*"
                    val contentIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = mimeType
                    }
                    fileChooserLauncher.launch(contentIntent)
                    true
                } catch (_: Exception) {
                    this@MainActivity.fileUploadCallback = null
                    Toast.makeText(this@MainActivity, "No se pudo abrir la galería", Toast.LENGTH_SHORT).show()
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
        swipeRefresh.isEnabled = false
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

    private fun launchCameraCapture(): Boolean {
        return try {
            val photoFile = File.createTempFile("prenda_", ".jpg", cacheDir)
            val photoUri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                photoFile
            )
            cameraPhotoUri = photoUri
            val captureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            fileChooserLauncher.launch(captureIntent)
            true
        } catch (_: Exception) {
            cameraPhotoUri = null
            fileUploadCallback?.onReceiveValue(null)
            fileUploadCallback = null
            Toast.makeText(this, "No se pudo abrir la cámara", Toast.LENGTH_SHORT).show()
            false
        }
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
