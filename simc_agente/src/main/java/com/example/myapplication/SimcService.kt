package com.example.myapplication

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.Camera
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Suppress("DEPRECATION")
class SimcService : Service() {

    private var socket: Socket? = null
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var isBloqueoTotal = false

    private val appsCongeladas = HashSet<String>()
    private var overlayAppCongelada: View? = null

    private var overlayMensajeDocente: View? = null
    private val appsPermitidas = HashSet<String>()
    private var camOrientation = 270

    private val handler = Handler(Looper.getMainLooper())
    private var serverUrl = "https://app.simc.space"
    private var token = ""
    private var alumno = ""
    private var pcId = ""

    private var appActual = "SIMC Móvil"
    private var inicioApp = System.currentTimeMillis()
    private var timeInCurrent = 0
    private var tiempos = HashMap<String, Long>()

    // Cámara en vivo
    private var camera: Camera? = null
    private var isStreamingCamera = false
    private var ultimoFrameCamara = 0L

    // Transmisión de pantalla en vivo
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var isStreamingPantalla = false
    private var ultimoFramePantalla = 0L

    companion object {
        var isRunning = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        getSharedPreferences("SIMC_PREFS", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("is_servicio_activo", true)
            .apply()

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        crearCanalNotificacion()
        startForeground(1001, crearNotificacion("SIMC Conectando..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            if (intent.action == "ACTION_SOLICITAR_SALIDA") {
                enviarSolicitudSalida()
                return START_STICKY
            }

            if (intent.action == "ACTION_SOLICITAR_AYUDA") {
                val msgAyuda = intent.getStringExtra("MENSAJE_AYUDA") ?: "El alumno solicita asistencia"
                enviarSolicitudAyuda(msgAyuda)
                return START_STICKY
            }

            serverUrl = intent.getStringExtra("SERVER_URL") ?: "https://app.simc.space"
            token = intent.getStringExtra("TOKEN") ?: ""
            alumno = intent.getStringExtra("ALUMNO") ?: "Celular Alumno"
            pcId = "MOVIL-${Build.MODEL.replace(" ", "_")}"

            if (token.isNotEmpty()) {
                getSharedPreferences("SIMC_PREFS", Context.MODE_PRIVATE)
                    .edit()
                    .putString("token", token)
                    .putString("alumno", alumno)
                    .putBoolean("is_servicio_activo", true)
                    .apply()
            }

            conectarSocket()
            iniciarBucleMonitoreo()
        }
        return START_STICKY
    }

    private fun conectarSocket() {
        try {
            var url = serverUrl.trim()
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = if (url.contains("simc.space")) "https://$url" else "http://$url"
            }

            val opts = IO.Options().apply {
                reconnection = true
                reconnectionAttempts = Int.MAX_VALUE
                reconnectionDelay = 3000
                timeout = 10000
            }

            socket = IO.socket(url, opts)

            socket?.on(Socket.EVENT_CONNECT) {
                val joinData = JSONObject().apply {
                    put("token", token)
                    put("pc_id", pcId)
                    put("alumno", alumno)
                    put("ip", "Móvil Android")
                }
                socket?.emit("join_sala_cliente", joinData)
                handler.post { actualizarNotificacion("Conectado a la sala SIMC") }
            }

            socket?.on("bloquear_pc") { args ->
                var msg = "Dispositivo bloqueado por el supervisor"
                if (args.isNotEmpty() && args[0] is JSONObject) {
                    msg = (args[0] as JSONObject).optString("mensaje", msg)
                }
                handler.post { mostrarBloqueoTotal(msg) }
            }

            socket?.on("desbloquear_pc") {
                handler.post { quitarBloqueoTotal() }
            }

            socket?.on("accion_proceso") { args ->
                if (args.isNotEmpty() && args[0] is JSONObject) {
                    val data = args[0] as JSONObject
                    val accion = data.optString("accion", "kill")
                    val app = data.optString("app", "").trim().lowercase()

                    handler.post {
                        when (accion) {
                            "suspend" -> {
                                val target = if (app.isNotEmpty()) app else appActual.lowercase()
                                appsCongeladas.add(target)
                                reportarAccionProceso("suspend", target, true, "Aplicación '$target' congelada")
                                verificarAppCongelada()
                            }
                            "resume" -> {
                                if (app.isNotEmpty()) appsCongeladas.remove(app) else appsCongeladas.clear()
                                reportarAccionProceso("resume", app, true, "Aplicación reanudada")
                                quitarOverlayCongelado()
                            }
                            "kill" -> {
                                val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                                    addCategory(Intent.CATEGORY_HOME)
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                startActivity(homeIntent)
                                reportarAccionProceso("kill", app, true, "Aplicación cerrada")
                            }
                        }
                    }
                }
            }

            socket?.on("salida_autorizada") {
                handler.post {
                    sendBroadcast(Intent("SIMC_SALIDA_AUTORIZADA"))
                    stopSelf()
                }
            }

            socket?.on("salida_rechazada") {
                handler.post { sendBroadcast(Intent("SIMC_SALIDA_RECHAZADA")) }
            }

            socket?.on("iniciar_stream_camara") {
                handler.post { iniciarCamara() }
            }

            socket?.on("detener_stream_camara") {
                handler.post { detenerCamara() }
            }

            // Stream de Pantalla del dispositivo
            socket?.on("iniciar_stream_pantalla") {
                handler.post { iniciarStreamPantalla() }
            }

            socket?.on("detener_stream_pantalla") {
                handler.post { detenerStreamPantalla() }
            }

            // Mensajes directos / Avisos del docente
            socket?.on("mensaje_docente") { args ->
                var msg = "Aviso del profesor"
                if (args.isNotEmpty()) {
                    if (args[0] is JSONObject) {
                        msg = (args[0] as JSONObject).optString("mensaje", msg)
                    } else if (args[0] is String) {
                        msg = args[0] as String
                    }
                }
                handler.post { mostrarMensajeDocenteOverlay(msg) }
            }

            // Lista blanca de aplicaciones permitidas
            socket?.on("establecer_lista_blanca") { args ->
                if (args.isNotEmpty() && args[0] is JSONObject) {
                    val data = args[0] as JSONObject
                    val list = data.optJSONArray("apps")
                    appsPermitidas.clear()
                    if (list != null) {
                        for (i in 0 until list.length()) {
                            appsPermitidas.add(list.optString(i).lowercase())
                        }
                    }
                }
            }

            socket?.connect()

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun enviarSolicitudSalida() {
        val hora = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val data = JSONObject().apply {
            put("token", token)
            put("pc_id", pcId)
            put("alumno", alumno)
            put("hora", hora)
        }
        socket?.emit("solicitar_salida", data)
    }

    private fun enviarSolicitudAyuda(mensaje: String) {
        val hora = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val data = JSONObject().apply {
            put("token", token)
            put("pc_id", pcId)
            put("alumno", alumno)
            put("mensaje", mensaje)
            put("hora", hora)
        }
        socket?.emit("solicitar_ayuda", data)
    }

    private fun mostrarMensajeDocenteOverlay(mensaje: String) {
        if (!Settings.canDrawOverlays(this)) return
        try {
            quitarMensajeDocenteOverlay()
            val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
            overlayMensajeDocente = inflater.inflate(R.layout.layout_overlay_mensaje, null)
            overlayMensajeDocente?.findViewById<TextView>(R.id.txtMensajeDocente)?.text = mensaje
            overlayMensajeDocente?.findViewById<View>(R.id.btnEntendidoMensaje)?.setOnClickListener {
                confirmarRecepcionMensaje()
                quitarMensajeDocenteOverlay()
            }

            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.CENTER }

            windowManager?.addView(overlayMensajeDocente, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun quitarMensajeDocenteOverlay() {
        if (overlayMensajeDocente != null) {
            try { windowManager?.removeView(overlayMensajeDocente) } catch (e: Exception) {}
            overlayMensajeDocente = null
        }
    }

    private fun confirmarRecepcionMensaje() {
        val json = JSONObject().apply {
            put("token", token)
            put("pc_id", pcId)
            put("alumno", alumno)
            put("confirmado", true)
        }
        socket?.emit("confirmar_mensaje", json)
    }

    private fun reportarAccionProceso(accion: String, app: String, success: Boolean, msg: String) {
        val json = JSONObject().apply {
            put("token", token)
            put("pc_id", pcId)
            put("accion", accion)
            put("app", app)
            put("success", success)
            put("mensaje", msg)
            put("procesos_suspendidos", obtenerListaSuspendidos())
        }
        socket?.emit("reporte_accion_proceso", json)
    }

    private fun obtenerListaSuspendidos(): JSONArray {
        val arr = JSONArray()
        val hora = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        for (a in appsCongeladas) {
            arr.put(JSONObject().apply {
                put("pid", a.hashCode())
                put("name", a)
                put("time", hora)
            })
        }
        return arr
    }

    // ─────────────── TRANSMISIÓN DE PANTALLA EN VIVO ───────────────

    private fun iniciarStreamPantalla() {
        if (isStreamingPantalla) return
        val code = MainActivity.mediaProjectionResultCode
        val data = MainActivity.mediaProjectionData
        if (code == 0 || data == null) return

        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpm.getMediaProjection(code, data)

            val metrics = DisplayMetrics()
            windowManager?.defaultDisplay?.getRealMetrics(metrics)

            // Escalar a resolución liviana y fluida (ej. 360 de ancho)
            val width = 360
            val height = (metrics.heightPixels * 360) / metrics.widthPixels
            val density = metrics.densityDpi

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "SIMC_Screen",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, null
            )

            imageReader?.setOnImageAvailableListener({ reader ->
                val ahora = System.currentTimeMillis()
                if (ahora - ultimoFramePantalla >= 150) { // ~7 FPS
                    ultimoFramePantalla = ahora
                    val image = reader.acquireLatestImage()
                    if (image != null) {
                        try {
                            val planes = image.planes
                            val buffer = planes[0].buffer
                            val pixelStride = planes[0].pixelStride
                            val rowStride = planes[0].rowStride
                            val rowPadding = rowStride - pixelStride * width

                            val bitmap = Bitmap.createBitmap(
                                width + rowPadding / pixelStride,
                                height, Bitmap.Config.ARGB_8888
                            )
                            bitmap.copyPixelsFromBuffer(buffer)
                            image.close()

                            val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                            val out = ByteArrayOutputStream()
                            cropped.compress(Bitmap.CompressFormat.JPEG, 45, out)
                            val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)

                            val json = JSONObject().apply {
                                put("token", token)
                                put("pc_id", pcId)
                                put("frame", b64)
                            }
                            socket?.emit("stream_frame_pantalla", json)
                        } catch (e: Exception) {
                            try { image.close() } catch (ex: Exception) {}
                        }
                    }
                } else {
                    val img = reader.acquireLatestImage()
                    img?.close()
                }
            }, handler)

            isStreamingPantalla = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun detenerStreamPantalla() {
        try {
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            mediaProjection?.stop()
            mediaProjection = null
        } catch (e: Exception) {}
        isStreamingPantalla = false
    }

    // ─────────────── MONITOREO Y CONGELAR APP ESPECÍFICA ───────────────

    private val loopRunnable = object : Runnable {
        override fun run() {
            try {
                detectarAppEnPrimerPlano()
                verificarAppCongelada()
                verificarListaBlanca()
                enviarReporteAlServidor()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            handler.postDelayed(this, 1500)
        }
    }

    private fun iniciarBucleMonitoreo() {
        handler.removeCallbacks(loopRunnable)
        handler.post(loopRunnable)
    }

    private fun detectarAppEnPrimerPlano() {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val time = System.currentTimeMillis()
        val events = usm.queryEvents(time - 1000 * 10, time)
        val event = UsageEvents.Event()
        var lastPackage = ""

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                lastPackage = event.packageName
            }
        }

        if (lastPackage.isNotEmpty() && lastPackage != packageName) {
            val nombreAmigable = obtenerNombreApp(lastPackage)
            val ahora = System.currentTimeMillis()

            if (appActual != nombreAmigable) {
                if (appActual.isNotEmpty()) {
                    val duracion = (ahora - inicioApp) / 1000
                    tiempos[appActual] = (tiempos[appActual] ?: 0L) + duracion
                }
                appActual = nombreAmigable
                inicioApp = ahora
                timeInCurrent = 0
            } else {
                timeInCurrent = ((ahora - inicioApp) / 1000).toInt()
            }
        }
    }

    private fun verificarAppCongelada() {
        if (isBloqueoTotal) return

        val appLower = appActual.lowercase()
        val debeEstarCongelada = appsCongeladas.any { appLower.contains(it) || it.contains(appLower) }

        if (debeEstarCongelada) {
            mostrarOverlayCongelado("❄️ '$appActual' está pausada por el profesor")
        } else {
            quitarOverlayCongelado()
        }
    }

    private fun mostrarOverlayCongelado(mensaje: String) {
        if (overlayAppCongelada != null || !Settings.canDrawOverlays(this)) return
        try {
            val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
            overlayAppCongelada = inflater.inflate(R.layout.layout_overlay_lock, null)
            overlayAppCongelada?.findViewById<TextView>(R.id.txtMensajeBloqueo)?.text = mensaje

            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.CENTER }

            windowManager?.addView(overlayAppCongelada, params)
        } catch (e: Exception) {}
    }

    private fun quitarOverlayCongelado() {
        if (overlayAppCongelada != null) {
            try { windowManager?.removeView(overlayAppCongelada) } catch (e: Exception) {}
            overlayAppCongelada = null
        }
    }

    private fun verificarListaBlanca() {
        if (isBloqueoTotal || appsPermitidas.isEmpty()) return
        val appLower = appActual.lowercase()
        val esPermitida = appsPermitidas.any { appLower.contains(it) || it.contains(appLower) } ||
                appLower.contains("simc") || appLower.contains("launcher") || appLower.contains("inicio")

        if (!esPermitida) {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(homeIntent)
        }
    }

    private fun obtenerNombreApp(packageName: String): String {
        return try {
            val pm = applicationContext.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            when {
                packageName.contains("youtube") -> "YouTube"
                packageName.contains("chrome") -> "Google Chrome"
                packageName.contains("whatsapp") -> "WhatsApp"
                packageName.contains("tiktok") -> "TikTok"
                packageName.contains("roblox") -> "Roblox"
                else -> packageName
            }
        }
    }

    private fun clasificarApp(app: String): String {
        val noProd = listOf("youtube", "tiktok", "instagram", "roblox", "free fire", "juego", "facebook", "twitter", "netflix", "spotify")
        val appLower = app.lowercase()
        return if (noProd.any { appLower.contains(it) }) "no_productiva" else "productiva"
    }

    private fun enviarReporteAlServidor() {
        if (socket?.connected() != true) return

        val cat = clasificarApp(appActual)
        val icono = if (cat == "productiva") "📱" else "🎮"

        val report = JSONObject().apply {
            put("app", appActual)
            put("icon", icono)
            put("cat", cat)
            put("prodPct", if (cat == "productiva") 90 else 10)
            put("ocioPct", if (cat == "no_productiva") 90 else 10)
            put("timeInApp", timeInCurrent)
            put("titulo", appActual)
            put("is_pc_blocked", isBloqueoTotal)
            put("suspended_processes", obtenerListaSuspendidos())
        }
        socket?.emit("client_update", report)
    }

    // ─────────────── BLOQUEO TOTAL DE PANTALLA ───────────────

    private fun mostrarBloqueoTotal(mensaje: String) {
        if (isBloqueoTotal || !Settings.canDrawOverlays(this)) return
        try {
            quitarOverlayCongelado()
            val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
            overlayView = inflater.inflate(R.layout.layout_overlay_lock, null)
            overlayView?.findViewById<TextView>(R.id.txtMensajeBloqueo)?.text = mensaje

            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.CENTER }

            windowManager?.addView(overlayView, params)
            isBloqueoTotal = true
        } catch (e: Exception) {}
    }

    private fun quitarBloqueoTotal() {
        if (overlayView != null) {
            try { windowManager?.removeView(overlayView) } catch (e: Exception) {}
            overlayView = null
        }
        isBloqueoTotal = false
    }

    // ─────────────── CÁMARA EN VIVO ───────────────

    private fun iniciarCamara() {
        if (isStreamingCamera || ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return

        try {
            var camId = 0
            val numCams = Camera.getNumberOfCameras()
            for (i in 0 until numCams) {
                val info = Camera.CameraInfo()
                Camera.getCameraInfo(i, info)
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                    camId = i
                    camOrientation = info.orientation
                    break
                }
            }

            camera = Camera.open(camId)
            val params = camera?.parameters
            val sizes = params?.supportedPreviewSizes
            if (!sizes.isNullOrEmpty()) {
                val s = sizes.minByOrNull { it.width * it.height } ?: sizes[0]
                params.setPreviewSize(s.width, s.height)
            }
            camera?.parameters = params

            camera?.setPreviewCallback { data, cam ->
                val ahora = System.currentTimeMillis()
                if (ahora - ultimoFrameCamara >= 100) {
                    ultimoFrameCamara = ahora
                    enviarFrameCamara(data, cam)
                }
            }
            camera?.startPreview()
            isStreamingCamera = true
        } catch (e: Exception) {}
    }

    private fun enviarFrameCamara(data: ByteArray, cam: Camera) {
        try {
            val size = cam.parameters.previewSize
            val yuvImage = YuvImage(data, ImageFormat.NV21, size.width, size.height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, size.width, size.height), 50, out)
            val rawBytes = out.toByteArray()

            // Corregir orientación de la cámara frontal
            val bitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size)
            val matrix = Matrix()
            val rot = if (camOrientation == 0) 270f else camOrientation.toFloat()
            matrix.postRotate(rot)

            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            val finalOut = ByteArrayOutputStream()
            rotated.compress(Bitmap.CompressFormat.JPEG, 45, finalOut)

            val b64 = Base64.encodeToString(finalOut.toByteArray(), Base64.NO_WRAP)

            val json = JSONObject().apply {
                put("token", token)
                put("pc_id", pcId)
                put("frame", b64)
            }
            socket?.emit("stream_frame_camara", json)
        } catch (e: Exception) {}
    }

    private fun detenerCamara() {
        try {
            camera?.stopPreview()
            camera?.setPreviewCallback(null)
            camera?.release()
            camera = null
        } catch (e: Exception) {}
        isStreamingCamera = false
    }

    // ─────────────── NOTIFICACIÓN ───────────────

    private fun crearCanalNotificacion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("simc_channel", "SIMC Monitoreo", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun crearNotificacion(texto: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, "simc_channel")
            .setContentTitle("SIMC Agente Móvil")
            .setContentText(texto)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentIntent(pIntent)
            .setOngoing(true)
            .build()
    }

    private fun actualizarNotificacion(texto: String) {
        getSystemService(NotificationManager::class.java)?.notify(1001, crearNotificacion(texto))
    }

    override fun onDestroy() {
        isRunning = false
        getSharedPreferences("SIMC_PREFS", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("is_servicio_activo", false)
            .apply()

        handler.removeCallbacks(loopRunnable)
        quitarBloqueoTotal()
        quitarOverlayCongelado()
        detenerCamara()
        detenerStreamPantalla()
        try {
            socket?.disconnect()
            socket?.off()
        } catch (e: Exception) {}
        super.onDestroy()
    }
}
