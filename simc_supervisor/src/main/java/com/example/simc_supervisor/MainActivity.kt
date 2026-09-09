package com.example.simc_supervisor

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar

    companion object {
        // Nube oficial:
        private const val URL_SUPERVISOR = "https://app.simc.space/panel_m.html"
        // Si pruebas en red local con XAMPP / Python Flask:
        // private const val URL_SUPERVISOR = "http://192.168.1.100:5000/panel_m.html"

        private const val PERMISSION_REQ_CODE = 101
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webViewSupervisor)
        progressBar = findViewById(R.id.progressBar)

        solicitarPermisosHardware()
        configurarWebView()
        configurarNavegacionAtras()

        // Cargar el panel del Supervisor
        webView.loadUrl(URL_SUPERVISOR)
    }

    private fun solicitarPermisosHardware() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val permisos = arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CAMERA
            )
            val pendientes = permisos.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (pendientes.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, pendientes.toTypedArray(), PERMISSION_REQ_CODE)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configurarWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false // Permite streaming de video/audio sin trabas
            allowFileAccess = true
            cacheMode = WebSettings.LOAD_DEFAULT

            // Ajuste responsivo de pantalla para que no se corten las opciones ni grillas
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            textZoom = 100
        }

        // Aceleración por hardware para que las transmisiones de cámaras vayan a 60 FPS
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        // Navegación dentro de la misma App (sin abrir Chrome externo)
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                progressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progressBar.visibility = View.GONE
            }
        }

        // Concede permisos WebRTC internos (imprescindible para el micrófono e intercomunicador)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                runOnUiThread {
                    request?.grant(request.resources)
                }
            }
        }
    }

    // Manejo moderno del botón Atrás en Android (sin deprecaciones)
    private fun configurarNavegacionAtras() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }
}
