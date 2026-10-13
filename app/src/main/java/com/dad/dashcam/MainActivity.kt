package com.dad.dashcam

import android.content.ContentValues
import android.content.Context
import android.net.*
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.webkit.URLUtil
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private var boundNetwork: Network? = null
    private val dashcamUrl = "http://192.168.10.1:8082/"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            loadWithOverviewMode = true
            useWideViewPort = true
            mediaPlaybackRequiresUserGesture = false
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false
            }
        }

        webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            downloadViaBoundWifi(url, fileName, mimeType ?: "video/mp4")
        }

        bindToDashcamWifiAndLoad()
    }

    private fun bindToDashcamWifiAndLoad() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val existingWifi = cm.allNetworks.firstOrNull { net ->
            cm.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }

        if (existingWifi != null) {
            activateNetwork(cm, existingWifi)
            return
        }

        Toast.makeText(this, "Waiting for Dashcam Wi-Fi...", Toast.LENGTH_SHORT).show()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        cm.requestNetwork(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread { activateNetwork(cm, network) }
            }
        })
    }

    private fun activateNetwork(cm: ConnectivityManager, network: Network) {
        boundNetwork = network
        cm.bindProcessToNetwork(network)
        webView.loadUrl(dashcamUrl)
    }

    private fun downloadViaBoundWifi(fileUrl: String, fileName: String, mimeType: String) {
        Toast.makeText(this, "Saving $fileName to Downloads...", Toast.LENGTH_LONG).show()
        thread {
            try {
                val net = boundNetwork
                val url = URL(fileUrl)
                val conn = (if (net != null) net.openConnection(url) else url.openConnection()) as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 30000
                conn.connect()

                val resolver = applicationContext.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Dashcam")
                    }
                }

                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    conn.inputStream.use { input: InputStream ->
                        resolver.openOutputStream(uri)?.use { output ->
                            input.copyTo(output, bufferSize = 64 * 1024)
                        }
                    }
                    runOnUiThread {
                        Toast.makeText(this, "Saved to Downloads/Dashcam: $fileName", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
