package com.example.simc_individual

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

class SimcIndividualService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var isServicioCorriendo = false
    private var ultimaAppNotificada = ""

    companion object {
        var isRunning = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        crearCanalNotificacion()
        startForeground(2001, crearNotificacion("Monitoreando concentración en segundo plano..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isServicioCorriendo) {
            isServicioCorriendo = true
            iniciarBucleMonitoreo()
        }
        return START_STICKY
    }

    private val loopMonitoreo = object : Runnable {
        override fun run() {
            try {
                detectarAppSegundoplano()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            handler.postDelayed(this, 2000)
        }
    }

    private fun iniciarBucleMonitoreo() {
        handler.removeCallbacks(loopMonitoreo)
        handler.post(loopMonitoreo)
    }

    private fun detectarAppSegundoplano() {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val ahora = System.currentTimeMillis()
        val events = usm.queryEvents(ahora - 1000 * 15, ahora)
        val event = UsageEvents.Event()
        var appActualPkg = ""

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                appActualPkg = event.packageName
            }
        }

        if (appActualPkg.isNotEmpty() && appActualPkg != packageName) {
            val appLower = appActualPkg.lowercase()
            val esDistraccion = appLower.contains("whatsapp") || appLower.contains("youtube") ||
                    appLower.contains("tiktok") || appLower.contains("instagram") ||
                    appLower.contains("facebook") || appLower.contains("twitter") ||
                    appLower.contains("roblox") || appLower.contains("game")

            val nombreApp = try {
                val pm = packageManager
                val info = pm.getApplicationInfo(appActualPkg, 0)
                pm.getApplicationLabel(info).toString()
            } catch (e: Exception) {
                appActualPkg
            }

            // Guardar app actual en SharedPreferences para la WebView
            val prefs = getSharedPreferences("SIMC_IND_PREFS", Context.MODE_PRIVATE)
            val alertas = prefs.getInt("alertas_count", 0)

            if (esDistraccion) {
                if (ultimaAppNotificada != appActualPkg) {
                    ultimaAppNotificada = appActualPkg
                    val nuevasAlertas = alertas + 1
                    prefs.edit()
                        .putString("app_actual", nombreApp)
                        .putBoolean("es_distraccion", true)
                        .putInt("alertas_count", nuevasAlertas)
                        .apply()

                    notificarDistraccion("⚠️ Distracción detectada: Usando $nombreApp")
                }
            } else {
                ultimaAppNotificada = ""
                prefs.edit()
                    .putString("app_actual", nombreApp)
                    .putBoolean("es_distraccion", false)
                    .apply()
            }
        }
    }

    private fun crearCanalNotificacion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("simc_ind_channel", "SIMC Individual Monitoreo", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun crearNotificacion(texto: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, "simc_ind_channel")
            .setContentTitle("⚡ SIMC Individual")
            .setContentText(texto)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pIntent)
            .setOngoing(true)
            .build()
    }

    private fun notificarDistraccion(textoMensaje: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return
            }
        }
        val nm = getSystemService(NotificationManager::class.java)
        val notif = NotificationCompat.Builder(this, "simc_ind_channel")
            .setContentTitle("⚡ SIMC Individual Alerta")
            .setContentText(textoMensaje)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVibrate(longArrayOf(0, 300, 100, 300))
            .build()
        nm?.notify(2002, notif)
    }

    override fun onDestroy() {
        isRunning = false
        isServicioCorriendo = false
        handler.removeCallbacks(loopMonitoreo)
        super.onDestroy()
    }
}
