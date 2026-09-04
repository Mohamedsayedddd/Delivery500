package com.delivery.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.content.getSystemService
import com.delivery.app.databinding.ActivityMainBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val siteUrl by lazy { getString(R.string.site_url) }

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraImageUri: Uri? = null
    private var pendingFileChooserParams: WebChromeClient.FileChooserParams? = null

    private var lastBackPressTime = 0L
    private var hasStartedLoading = false

    // ---- Activity result launchers -----------------------------------

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = filePathCallback
        filePathCallback = null
        if (callback == null) return@registerForActivityResult

        if (result.resultCode != RESULT_OK) {
            callback.onReceiveValue(null)
            return@registerForActivityResult
        }

        val data = result.data
        val results: Array<Uri>? = when {
            data?.dataString != null -> arrayOf(Uri.parse(data.dataString))
            data?.clipData != null -> {
                val clip = data.clipData!!
                Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
            }
            cameraImageUri != null -> arrayOf(cameraImageUri!!)
            else -> null
        }
        callback.onReceiveValue(results)
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { launchFileChooserIntent() }

    // ---- Lifecycle ------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setTheme(R.style.Theme_Delivery)

        setupWebView()
        setupSwipeRefresh()
        setupRetryButton()
        setupBackNavigation()

        if (savedInstanceState == null) {
            loadSiteIfConnected()
        } else {
            binding.webView.restoreState(savedInstanceState)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        binding.webView.onResume()
    }

    override fun onPause() {
        binding.webView.onPause()
        // Persist cookies (session/login) across app restarts.
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        binding.webView.apply {
            (parent as? android.view.ViewGroup)?.removeView(this)
            stopLoading()
            settings.javaScriptEnabled = false
            clearHistory()
            removeAllViews()
            destroy()
        }
        super.onDestroy()
    }

    // ---- WebView setup ----------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webView = binding.webView
        val settings: WebSettings = webView.settings

        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.setSupportMultipleWindows(false)
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

        // Pinch-zoom enabled, without the on-screen +/- controls.
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false

        settings.allowFileAccess = true
        settings.allowContentAccess = true

        // Cookies / sessions.
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = deliveryWebViewClient
        webView.webChromeClient = deliveryWebChromeClient
        webView.setDownloadListener(downloadListener)
    }

    private val deliveryWebViewClient = object : WebViewClient() {

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ): Boolean {
            val uri = request.url
            val scheme = uri.scheme?.lowercase(Locale.ROOT)

            // Regular web pages (any host) stay inside the WebView, exactly
            // like a mobile browser tab — this keeps the whole site,
            // including third-party checkout/redirect pages, in-app.
            if (scheme == "http" || scheme == "https") {
                return false
            }

            // Non-web schemes (tel, mailto, sms, whatsapp, geo, intent...)
            // genuinely require an external app, so hand them off.
            return openExternally(uri)
        }

        @Suppress("DEPRECATION")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // Handled by the WebResourceRequest overload above.
                return false
            }
            val uri = Uri.parse(url)
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            if (scheme == "http" || scheme == "https") return false
            return openExternally(uri)
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            super.onPageStarted(view, url, favicon)
            hasStartedLoading = true
            showLoading()
        }

        override fun onPageFinished(view: WebView, url: String?) {
            super.onPageFinished(view, url)
            binding.swipeRefresh.isRefreshing = false
            binding.progressBar.visibility = View.GONE
            binding.centerLoader.visibility = View.GONE
            showContent()
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            super.onReceivedError(view, request, error)
            if (request.isForMainFrame) {
                showError()
            }
        }

        @Suppress("DEPRECATION")
        override fun onReceivedError(
            view: WebView,
            errorCode: Int,
            description: String?,
            failingUrl: String?
        ) {
            super.onReceivedError(view, errorCode, description, failingUrl)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                showError()
            }
        }
    }

    private val deliveryWebChromeClient = object : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            super.onProgressChanged(view, newProgress)
            binding.progressBar.progress = newProgress
            if (newProgress >= 100) {
                binding.progressBar.visibility = View.GONE
            } else if (binding.errorLayout.visibility != View.VISIBLE) {
                binding.progressBar.visibility = View.VISIBLE
            }
        }

        override fun onShowFileChooser(
            webView: WebView,
            callback: ValueCallback<Array<Uri>>,
            params: FileChooserParams
        ): Boolean {
            filePathCallback?.onReceiveValue(null)
            filePathCallback = callback
            pendingFileChooserParams = params

            val wantsCamera = params.acceptTypes?.any { it.contains("image") } == true
            val hasCameraPermission = ContextCompatPermission(Manifest.permission.CAMERA)

            if (wantsCamera && !hasCameraPermission) {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            } else {
                launchFileChooserIntent()
            }
            return true
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            // Grant camera/mic access to the page only if the user has
            // already granted the corresponding Android runtime permission.
            val granted = request.resources.filter { resource ->
                when (resource) {
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE ->
                        ContextCompatPermission(Manifest.permission.CAMERA)
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
                        ContextCompatPermission(Manifest.permission.RECORD_AUDIO)
                    else -> false
                }
            }.toTypedArray()

            runOnUiThread {
                if (granted.isNotEmpty()) request.grant(granted) else request.deny()
            }
        }
    }

    private val downloadListener = DownloadListener { url, _, contentDisposition, mimeType, _ ->
        try {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType)
                addRequestHeader("cookie", CookieManager.getInstance().getCookie(url))
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                val fileName = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }
            val dm = getSystemService<DownloadManager>()
            dm?.enqueue(request)
            Toast.makeText(this, fileName(url) + " …", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.w("Delivery", "Download failed, falling back to browser", e)
            openExternally(Uri.parse(url))
        }
    }

    private fun fileName(url: String) = android.webkit.URLUtil.guessFileName(url, null, null)

    // ---- File chooser / camera helpers ------------------------------------

    private fun launchFileChooserIntent() {
        val params = pendingFileChooserParams
        val callback = filePathCallback
        if (params == null || callback == null) return

        val intents = mutableListOf<Intent>()

        if (ContextCompatPermission(Manifest.permission.CAMERA)) {
            createCameraIntent()?.let { intents.add(it) }
        }

        val contentIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            val acceptTypes = params.acceptTypes?.filter { it.isNotBlank() }
            if (!acceptTypes.isNullOrEmpty()) {
                putExtra(Intent.EXTRA_MIME_TYPES, acceptTypes.toTypedArray())
            }
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
        }

        val chooserIntent = Intent.createChooser(contentIntent, null).apply {
            if (intents.isNotEmpty()) {
                putExtra(Intent.EXTRA_INITIAL_INTENTS, intents.toTypedArray())
            }
        }

        runCatching { fileChooserLauncher.launch(chooserIntent) }
            .onFailure {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = null
            }
    }

    private fun createCameraIntent(): Intent? {
        val photoFile = runCatching {
            val dir = File(externalCacheDir, "images").apply { mkdirs() }
            val name = "IMG_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
            File(dir, name)
        }.getOrNull() ?: return null

        val photoUri = FileProvider.getUriForFile(
            this, "$packageName.fileprovider", photoFile
        )
        cameraImageUri = photoUri

        return Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(android.provider.MediaStore.EXTRA_OUTPUT, photoUri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }

    private fun ContextCompatPermission(permission: String): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(this, permission) ==
            PackageManager.PERMISSION_GRANTED

    // ---- External links -----------------------------------------------

    private fun openExternally(uri: Uri): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, uri)
            startActivity(intent)
            true
        } catch (e: Exception) {
            Toast.makeText(this, R.string.error_load_failed, Toast.LENGTH_SHORT).show()
            false
        }
    }

    // ---- Swipe to refresh -----------------------------------------------

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setColorSchemeResources(R.color.brand_primary)
        binding.swipeRefresh.setOnRefreshListener {
            if (isNetworkAvailable()) {
                binding.webView.reload()
            } else {
                binding.swipeRefresh.isRefreshing = false
                showError()
            }
        }
    }

    // ---- Retry / connectivity --------------------------------------------

    private fun setupRetryButton() {
        binding.retryButton.setOnClickListener { loadSiteIfConnected() }
    }

    private fun loadSiteIfConnected() {
        if (isNetworkAvailable()) {
            showLoading()
            binding.webView.loadUrl(siteUrl)
        } else {
            showError()
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService<ConnectivityManager>() ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            @Suppress("DEPRECATION")
            cm.activeNetworkInfo?.isConnected == true
        }
    }

    // ---- UI state ----------------------------------------------------------

    private fun showLoading() {
        binding.errorLayout.visibility = View.GONE
        binding.swipeRefresh.visibility = View.VISIBLE
        if (!hasStartedLoading) {
            binding.centerLoader.visibility = View.VISIBLE
        }
    }

    private fun showContent() {
        binding.errorLayout.visibility = View.GONE
        binding.swipeRefresh.visibility = View.VISIBLE
        binding.centerLoader.visibility = View.GONE
    }

    private fun showError() {
        binding.swipeRefresh.isRefreshing = false
        binding.progressBar.visibility = View.GONE
        binding.centerLoader.visibility = View.GONE
        binding.swipeRefresh.visibility = View.GONE
        binding.errorLayout.visibility = View.VISIBLE
    }

    // ---- Back navigation ----------------------------------------------

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    binding.webView.canGoBack() -> binding.webView.goBack()
                    else -> confirmExit()
                }
            }
        })
    }

    private fun confirmExit() {
        val now = System.currentTimeMillis()
        if (now - lastBackPressTime < 2000) {
            finish()
        } else {
            lastBackPressTime = now
            Toast.makeText(this, R.string.exit_toast, Toast.LENGTH_SHORT).show()
        }
    }
}
