package com.example.myapplication

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var txtStatus: TextView
    private lateinit var etServer: EditText
    private lateinit var etToken: EditText
    private lateinit var etAlumno: EditText
    private lateinit var btnPermisoOverlay: Button
    private lateinit var btnPermisoUso: Button
    private lateinit var btnPermisoCamara: Button
    private lateinit var btnPermisoPantalla: Button
    private lateinit var btnPedirAyuda: Button
    private lateinit var btnConectar: Button
    private lateinit var prefs: SharedPreferences

    private var isServicioActivo = false

    companion object {
        var mediaProjectionResultCode = 0
        var mediaProjectionData: Intent? = null
    }

    private val receptorEventos = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "SIMC_SALIDA_AUTORIZADA" -> {
                    detenerServicioDefinitivo()
                    Toast.makeText(this@MainActivity, "Salida autorizada por el docente", Toast.LENGTH_LONG).show()
                }
                "SIMC_SALIDA_RECHAZADA" -> {
                    Toast.makeText(this@MainActivity, "El docente rechazo tu solicitud de desconexion", Toast.LENGTH_LONG).show()
                    btnConectar.text = "SOLICITAR SALIDA AL DOCENTE"
                    btnConectar.isEnabled = true
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("SIMC_PREFS", Context.MODE_PRIVATE)

        txtStatus = findViewById(R.id.txtStatus)
        etServer = findViewById(R.id.etServer)
        etToken = findViewById(R.id.etToken)
        etAlumno = findViewById(R.id.etAlumno)
        btnPermisoOverlay = findViewById(R.id.btnPermisoOverlay)
        btnPermisoUso = findViewById(R.id.btnPermisoUso)
        btnPermisoCamara = findViewById(R.id.btnPermisoCamara)
        btnPermisoPantalla = findViewById(R.id.btnPermisoPantalla)
        btnPedirAyuda = findViewById(R.id.btnPedirAyuda)
        btnConectar = findViewById(R.id.btnConectar)

        // URL Oficial Bloqueada
        etServer.setText("https://app.simc.space")
        etServer.isEnabled = false

        etToken.setText(prefs.getString("token", ""))
        etAlumno.setText(prefs.getString("alumno", "Alumno Móvil"))

        btnPermisoOverlay.setOnClickListener { solicitarPermisoOverlay() }
        btnPermisoUso.setOnClickListener { solicitarPermisoUso() }
        btnPermisoCamara.setOnClickListener { solicitarPermisoCamara() }
        btnPermisoPantalla.setOnClickListener { solicitarPermisoPantalla() }
        btnPedirAyuda.setOnClickListener { pedirAyudaDocente() }

        btnConectar.setOnClickListener {
            if (isServicioActivo) {
                pedirPermisoSalidaSupervisor()
            } else {
                iniciarAgente()
            }
        }

        val filter = IntentFilter().apply {
            addAction("SIMC_SALIDA_AUTORIZADA")
            addAction("SIMC_SALIDA_RECHAZADA")
        }
        ContextCompat.registerReceiver(this, receptorEventos, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onResume() {
        super.onResume()
        actualizarEstadoPermisos()
        comprobarEstadoServicio()
    }

    private fun comprobarEstadoServicio() {
        val servicioGuardadoActivo = prefs.getBoolean("is_servicio_activo", false)
        val tokenGuardado = prefs.getString("token", "") ?: ""
        val alumnoGuardado = prefs.getString("alumno", "") ?: ""

        if (SimcService.isRunning || (servicioGuardadoActivo && tokenGuardado.isNotEmpty())) {
            isServicioActivo = true
            txtStatus.text = "🟢 Agente Conectado"
            txtStatus.setTextColor(Color.parseColor("#4ADE80"))
            btnConectar.text = "SOLICITAR SALIDA AL DOCENTE"
            btnConectar.setBackgroundColor(Color.parseColor("#EAB308"))
            btnConectar.isEnabled = true
            btnPedirAyuda.visibility = android.view.View.VISIBLE
            etToken.isEnabled = false
            etAlumno.isEnabled = false

            if (!SimcService.isRunning && tokenGuardado.isNotEmpty()) {
                val intent = Intent(this, SimcService::class.java).apply {
                    putExtra("SERVER_URL", "https://app.simc.space")
                    putExtra("TOKEN", tokenGuardado)
                    putExtra("ALUMNO", alumnoGuardado)
                }
                ContextCompat.startForegroundService(this, intent)
            }
        } else {
            isServicioActivo = false
            txtStatus.text = "🔴 Desconectado"
            txtStatus.setTextColor(Color.parseColor("#F87171"))
            btnConectar.text = "CONECTAR AL SERVIDOR"
            btnConectar.setBackgroundColor(Color.parseColor("#0284C7"))
            btnConectar.isEnabled = true
            btnPedirAyuda.visibility = android.view.View.GONE
            etToken.isEnabled = true
            etAlumno.isEnabled = true
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(receptorEventos) } catch (e: Exception) {}
        super.onDestroy()
    }

    // ─────────────── PERMISOS ───────────────

    private fun tienePermisoOverlay(): Boolean = Settings.canDrawOverlays(this)

    private fun solicitarPermisoOverlay() {
        if (!tienePermisoOverlay()) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivity(intent)
        } else {
            Toast.makeText(this, "Permiso de superposición ya concedido", Toast.LENGTH_SHORT).show()
        }
    }

    private fun tienePermisoUso(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun solicitarPermisoUso() {
        if (!tienePermisoUso()) {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        } else {
            Toast.makeText(this, "Permiso de datos de uso ya concedido", Toast.LENGTH_SHORT).show()
        }
    }

    private fun tienePermisoCamara(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun solicitarPermisoCamara() {
        if (!tienePermisoCamara()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 101)
        } else {
            Toast.makeText(this, "Permiso de cámara ya concedido", Toast.LENGTH_SHORT).show()
        }
    }

    private fun solicitarPermisoPantalla() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), 102)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 102 && resultCode == Activity.RESULT_OK && data != null) {
            mediaProjectionResultCode = resultCode
            mediaProjectionData = data
            actualizarEstadoPermisos()
            Toast.makeText(this, "Permiso de pantalla concedido", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        actualizarEstadoPermisos()
    }

    private fun actualizarEstadoPermisos() {
        if (tienePermisoOverlay()) {
            btnPermisoOverlay.text = "✅ 1. Superposición Concedida"
            btnPermisoOverlay.setBackgroundColor(Color.parseColor("#166534"))
        } else {
            btnPermisoOverlay.text = "❌ 1. Permitir Congelar Pantalla"
            btnPermisoOverlay.setBackgroundColor(Color.parseColor("#334155"))
        }

        if (tienePermisoUso()) {
            btnPermisoUso.text = "✅ 2. Acceso a Apps Concedido"
            btnPermisoUso.setBackgroundColor(Color.parseColor("#166534"))
        } else {
            btnPermisoUso.text = "❌ 2. Permitir Monitoreo de Apps"
            btnPermisoUso.setBackgroundColor(Color.parseColor("#334155"))
        }

        if (tienePermisoCamara()) {
            btnPermisoCamara.text = "✅ 3. Permiso de Cámara Concedido"
            btnPermisoCamara.setBackgroundColor(Color.parseColor("#166534"))
        } else {
            btnPermisoCamara.text = "❌ 3. Permitir Cámara (Docente)"
            btnPermisoCamara.setBackgroundColor(Color.parseColor("#334155"))
        }

        if (mediaProjectionData != null) {
            btnPermisoPantalla.text = "✅ 4. Transmisión de Pantalla Lista"
            btnPermisoPantalla.setBackgroundColor(Color.parseColor("#166534"))
        } else {
            btnPermisoPantalla.text = "❌ 4. Permitir Ver Pantalla al Docente"
            btnPermisoPantalla.setBackgroundColor(Color.parseColor("#334155"))
        }
    }

    // ─────────────── CONECTAR / SALIDA CONTROLADA ───────────────

    private fun iniciarAgente() {
        val server = "https://app.simc.space"
        val token = etToken.text.toString().trim()
        val alumno = etAlumno.text.toString().trim()

        if (token.isEmpty() || alumno.isEmpty()) {
            Toast.makeText(this, "Completa el Token y tu Nombre", Toast.LENGTH_SHORT).show()
            return
        }

        if (!tienePermisoOverlay() || !tienePermisoUso()) {
            Toast.makeText(this, "Otorga los permisos 1 y 2 primero", Toast.LENGTH_LONG).show()
            return
        }

        prefs.edit()
            .putString("token", token)
            .putString("alumno", alumno)
            .putBoolean("is_servicio_activo", true)
            .apply()

        val intent = Intent(this, SimcService::class.java).apply {
            putExtra("SERVER_URL", server)
            putExtra("TOKEN", token)
            putExtra("ALUMNO", alumno)
        }
        ContextCompat.startForegroundService(this, intent)

        isServicioActivo = true
        txtStatus.text = "🟢 Agente Conectado"
        txtStatus.setTextColor(Color.parseColor("#4ADE80"))
        btnConectar.text = "SOLICITAR SALIDA AL DOCENTE"
        btnConectar.setBackgroundColor(Color.parseColor("#EAB308"))
        btnPedirAyuda.visibility = android.view.View.VISIBLE
        etToken.isEnabled = false
        etAlumno.isEnabled = false
    }

    private fun pedirAyudaDocente() {
        val input = EditText(this).apply {
            hint = "Escribe tu duda o mensaje (Opcional)"
            setPadding(32, 24, 32, 24)
        }
        AlertDialog.Builder(this)
            .setTitle("🙋‍♂️ Solicitar Ayuda")
            .setMessage("Se enviará una alerta directa al docente para pedir asistencia.")
            .setView(input)
            .setPositiveButton("Enviar Alerta") { _, _ ->
                val nota = input.text.toString().trim()
                val mensaje = if (nota.isNotEmpty()) nota else "El alumno solicita asistencia en su equipo."
                val intent = Intent(this, SimcService::class.java).apply {
                    action = "ACTION_SOLICITAR_AYUDA"
                    putExtra("MENSAJE_AYUDA", mensaje)
                }
                startService(intent)
                Toast.makeText(this, "Alerta de ayuda enviada al docente", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun pedirPermisoSalidaSupervisor() {
        AlertDialog.Builder(this)
            .setTitle("🚪 Solicitar Salida")
            .setMessage("No puedes desconectarte por tu cuenta. ¿Deseas enviar una solicitud al docente para que autorice tu salida?")
            .setPositiveButton("Solicitar Permiso") { _, _ ->
                val intent = Intent(this, SimcService::class.java).apply {
                    action = "ACTION_SOLICITAR_SALIDA"
                }
                startService(intent)
                btnConectar.isEnabled = false
                btnConectar.text = "Esperando autorización del docente..."
                Toast.makeText(this, "Solicitud de salida enviada al docente", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun detenerServicioDefinitivo() {
        stopService(Intent(this, SimcService::class.java))
        prefs.edit().putBoolean("is_servicio_activo", false).apply()
        isServicioActivo = false
        txtStatus.text = "🔴 Desconectado"
        txtStatus.setTextColor(Color.parseColor("#F87171"))
        btnConectar.text = "CONECTAR AL SERVIDOR"
        btnConectar.setBackgroundColor(Color.parseColor("#0284C7"))
        btnConectar.isEnabled = true
        btnPedirAyuda.visibility = android.view.View.GONE
        etToken.isEnabled = true
        etAlumno.isEnabled = true
    }
}
