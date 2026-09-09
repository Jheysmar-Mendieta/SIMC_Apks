package com.example.simc_individual

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject

class WebAppInterface(private val context: Context) {

    @JavascriptInterface
    fun tienePermisoUso(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    @JavascriptInterface
    fun solicitarPermisoUso() {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    @JavascriptInterface
    fun iniciarServicioSegundoplano() {
        val intent = Intent(context, SimcIndividualService::class.java)
        androidx.core.content.ContextCompat.startForegroundService(context, intent)
    }

    @JavascriptInterface
    fun detenerServicioSegundoplano() {
        val intent = Intent(context, SimcIndividualService::class.java)
        context.stopService(intent)
    }

    @JavascriptInterface
    fun obtenerAppActual(): String {
        if (!tienePermisoUso()) return "Sin permiso de uso"
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val ahora = System.currentTimeMillis()
        val events = usm.queryEvents(ahora - 1000 * 20, ahora)
        val event = UsageEvents.Event()
        var lastPkg = ""

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                lastPkg = event.packageName
            }
        }

        if (lastPkg.isNotEmpty()) {
            return try {
                val pm = context.packageManager
                val info = pm.getApplicationInfo(lastPkg, 0)
                pm.getApplicationLabel(info).toString()
            } catch (e: Exception) {
                lastPkg
            }
        }
        return "SIMC Individual"
    }

    @JavascriptInterface
    fun obtenerEstadisticasApps(): String {
        if (!tienePermisoUso()) {
            return JSONObject().apply {
                put("permiso", false)
                put("apps", JSONArray())
            }.toString()
        }

        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val ahora = System.currentTimeMillis()
        val inicioHoy = ahora - 1000 * 60 * 60 * 24 // Últimas 24 horas

        val statsList = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, inicioHoy, ahora)
        val pm = context.packageManager
        val appsArr = JSONArray()

        val listProcesada = statsList
            .filter { it.totalTimeInForeground > 1000 }
            .sortedByDescending { it.totalTimeInForeground }

        for (stat in listProcesada.take(20)) {
            val pkg = stat.packageName
            if (pkg.contains("launcher") || pkg.contains("systemui")) continue

            val nombre = try {
                val info = pm.getApplicationInfo(pkg, 0)
                pm.getApplicationLabel(info).toString()
            } catch (e: Exception) {
                pkg
            }

            val pkgLower = pkg.lowercase()
            val cat = when {
                pkgLower.contains("whatsapp") || pkgLower.contains("youtube") || pkgLower.contains("tiktok") ||
                        pkgLower.contains("instagram") || pkgLower.contains("facebook") || pkgLower.contains("roblox") ||
                        pkgLower.contains("game") || pkgLower.contains("twitter") || pkgLower.contains("netflix") -> "ocio"
                pkgLower.contains("simc") || pkgLower.contains("chrome") || pkgLower.contains("studio") ||
                        pkgLower.contains("vscode") || pkgLower.contains("notion") || pkgLower.contains("drive") ||
                        pkgLower.contains("pdf") || pkgLower.contains("docs") -> "productiva"
                else -> "neutra"
            }

            appsArr.put(JSONObject().apply {
                put("package", pkg)
                put("nombre", nombre)
                put("segundos", stat.totalTimeInForeground / 1000)
                put("categoria", cat)
            })
        }

        return JSONObject().apply {
            put("permiso", true)
            put("apps", appsArr)
        }.toString()
    }
}
