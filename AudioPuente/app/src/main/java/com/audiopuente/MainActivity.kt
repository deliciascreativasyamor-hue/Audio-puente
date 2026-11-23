package com.audiopuente

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val reqPerms = 10
    private val reqProj = 11
    private var muteBox = true
    private var nsd: NsdManager? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var hostInput: EditText? = null
    private var statusView: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showMenu()
    }

    // ---------- helpers de UI ----------
    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(48, 48, 48, 48)
        gravity = Gravity.CENTER_HORIZONTAL
    }

    private fun label(t: String, size: Float = 18f) = TextView(this).apply {
        text = t; textSize = size; setPadding(0, 16, 0, 16)
    }

    private fun btn(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; setOnClickListener { onClick() }
    }

    private fun show(col: LinearLayout) {
        setContentView(ScrollView(this).apply { addView(col) })
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    // ---------- pantallas ----------
    private fun showMenu() {
        stopDiscovery()
        val col = column()
        col.addView(label("Audio Puente", 28f))
        col.addView(label("Escuchá la tele con tus auriculares Bluetooth, sin usar el Bluetooth del box."))
        col.addView(btn("Soy la TV (enviar audio)") { showTv() })
        col.addView(btn("Soy el celular (escuchar en mis auriculares)") { showPhone() })
        show(col)
    }

    private fun showTv() {
        val col = column()
        col.addView(label("Modo TV", 26f))
        col.addView(label("IP de esta TV: ${localIp() ?: "no encontrada (¿está en Wi-Fi?)"}"))
        col.addView(CheckBox(this).apply {
            text = "Silenciar el volumen del box mientras transmite"
            isChecked = muteBox
            setOnCheckedChangeListener { _, c -> muteBox = c }
        })
        col.addView(btn("Empezar a transmitir") { startTv() })
        col.addView(btn("Detener") {
            startService(Intent(this, TvCaptureService::class.java).setAction(ACTION_STOP))
        })
        col.addView(btn("Volver") { showMenu() })
        show(col)
    }

    private fun showPhone() {
        val col = column()
        col.addView(label("Modo celular", 26f))
        statusView = label("Buscando la TV en la red...")
        col.addView(statusView)
        hostInput = EditText(this).apply {
            hint = "IP de la TV (se completa sola si la encuentra)"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        col.addView(hostInput)
        col.addView(btn("Conectar") {
            val h = hostInput?.text?.toString()?.trim().orEmpty()
            if (h.isEmpty()) {
                toast("Falta la IP de la TV")
            } else {
                startForegroundService(
                    Intent(this, PhonePlayerService::class.java).putExtra(EXTRA_HOST, h)
                )
                statusView?.text = "Conectado a $h. Tus auriculares Bluetooth tienen que estar conectados al celular."
            }
        })
        col.addView(btn("Desconectar") {
            startService(Intent(this, PhonePlayerService::class.java).setAction(ACTION_STOP))
            statusView?.text = "Desconectado."
        })
        col.addView(btn("Volver") { showMenu() })
        show(col)
        startDiscovery()
    }

    // ---------- modo TV ----------
    private fun startTv() {
        val need = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            need += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) need += Manifest.permission.POST_NOTIFICATIONS
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), reqPerms) else askProjection()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code != reqPerms) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            askProjection()
        else toast("Sin permiso de audio no se puede capturar el sonido de la TV")
    }

    private fun askProjection() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        try {
            startActivityForResult(mpm.createScreenCaptureIntent(), reqProj)
        } catch (e: Exception) {
            toast("Este box no permite la captura de audio (no tiene el diálogo de captura).")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != reqProj) return
        if (resultCode == RESULT_OK && data != null) {
            startForegroundService(
                Intent(this, TvCaptureService::class.java)
                    .putExtra(EXTRA_CODE, resultCode)
                    .putExtra(EXTRA_DATA, data)
                    .putExtra(EXTRA_MUTE, muteBox)
            )
            toast("Transmitiendo. Ahora abrí la app en el celular.")
        } else {
            toast("No se aceptó la captura")
        }
    }

    // ---------- modo celular: buscar la TV ----------
    private fun startDiscovery() {
        try {
            val m = getSystemService(NSD_SERVICE) as NsdManager
            nsd = m
            val l = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(t: String) {}
                override fun onServiceFound(s: NsdServiceInfo) {
                    m.resolveService(s, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(si: NsdServiceInfo, e: Int) {}
                        override fun onServiceResolved(si: NsdServiceInfo) {
                            val ip = si.host?.hostAddress ?: return
                            runOnUiThread {
                                hostInput?.setText(ip)
                                statusView?.text = "TV encontrada: $ip"
                            }
                        }
                    })
                }
                override fun onServiceLost(s: NsdServiceInfo) {}
                override fun onDiscoveryStopped(t: String) {}
                override fun onStartDiscoveryFailed(t: String, e: Int) {}
                override fun onStopDiscoveryFailed(t: String, e: Int) {}
            }
            discovery = l
            m.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (_: Exception) {
        }
    }

    private fun stopDiscovery() {
        try { discovery?.let { nsd?.stopServiceDiscovery(it) } } catch (_: Exception) {}
        discovery = null
    }

    override fun onDestroy() {
        stopDiscovery()
        super.onDestroy()
    }
}
