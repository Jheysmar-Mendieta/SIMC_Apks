package com.example.myapplication

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences("SIMC_PREFS", Context.MODE_PRIVATE)
            val isServicioActivo = prefs.getBoolean("is_servicio_activo", false)
            val token = prefs.getString("token", "") ?: ""
            val alumno = prefs.getString("alumno", "") ?: ""

            if (isServicioActivo && token.isNotEmpty()) {
                val serviceIntent = Intent(context, SimcService::class.java).apply {
                    putExtra("SERVER_URL", "https://app.simc.space")
                    putExtra("TOKEN", token)
                    putExtra("ALUMNO", alumno)
                }
                ContextCompat.startForegroundService(context, serviceIntent)
            }
        }
    }
}
