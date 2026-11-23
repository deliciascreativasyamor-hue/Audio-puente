package com.audiopuente

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer

/** Corre en el TV box: captura el audio del sistema y lo manda por UDP al celular. */
class TvCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var record: AudioRecord? = null
    private var socket: DatagramSocket? = null
    private var nsd: NsdManager? = null
    private var regListener: NsdManager.RegistrationListener? = null
    private var prevVolume = -1

    @Volatile private var running = false
    @Volatile private var client: InetSocketAddress? = null
    @Volatile private var lastHello = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY

        startForeground(
            1, buildNotif("Transmitiendo el audio de la TV"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )

        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf(); return START_NOT_STICKY
        }

        val code = intent.getIntExtra(EXTRA_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else
            @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
        if (data == null) { stopSelf(); return START_NOT_STICKY }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = mpm.getMediaProjection(code, data)
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, Handler(Looper.getMainLooper()))
        projection = proj

        val cfg = AudioPlaybackCaptureConfiguration.Builder(proj)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val rec = AudioRecord.Builder()
            .setAudioFormat(fmt)
            .setAudioPlaybackCaptureConfig(cfg)
            .setBufferSizeInBytes(PAYLOAD * 8)
            .build()
        record = rec

        val sock = DatagramSocket(PORT)
        sock.soTimeout = 1000
        socket = sock

        registerNsd()

        if (intent.getBooleanExtra(EXTRA_MUTE, true)) {
            val am = getSystemService(AudioManager::class.java)
            prevVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            try { am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0) } catch (_: Exception) {}
        }

        running = true
        Thread({ receiveLoop(sock) }, "puente-recibir").start()
        Thread({ sendLoop(rec, sock) }, "puente-enviar").start()
        return START_NOT_STICKY
    }

    /** Escucha los "HOLA" del celular (cada 2 s) y se acuerda de a quién mandarle el audio. */
    private fun receiveLoop(sock: DatagramSocket) {
        val buf = ByteArray(32)
        val p = DatagramPacket(buf, buf.size)
        while (running) {
            try {
                p.length = buf.size
                sock.receive(p)
            } catch (_: SocketTimeoutException) {
                if (client != null && SystemClock.elapsedRealtime() - lastHello > 6000) client = null
                continue
            } catch (_: Exception) {
                break
            }
            when (String(buf, 0, p.length)) {
                "HOLA" -> {
                    client = InetSocketAddress(p.address, p.port)
                    lastHello = SystemClock.elapsedRealtime()
                }
                "CHAU" -> client = null
            }
        }
    }

    private fun sendLoop(rec: AudioRecord, sock: DatagramSocket) {
        val out = ByteArray(HEADER + PAYLOAD)
        val bb = ByteBuffer.wrap(out)
        var seq = 0
        try {
            rec.startRecording()
            while (running) {
                val n = rec.read(out, HEADER, PAYLOAD, AudioRecord.READ_BLOCKING)
                if (n < 0) break
                if (n == 0) continue
                val c = client ?: continue
                bb.putInt(0, seq++)
                try {
                    sock.send(DatagramPacket(out, 0, HEADER + n, c))
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun registerNsd() {
        try {
            val m = getSystemService(NSD_SERVICE) as NsdManager
            val info = NsdServiceInfo().apply {
                serviceName = "AudioPuente TV"
                serviceType = SERVICE_TYPE
                port = PORT
            }
            val l = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(i: NsdServiceInfo) {}
                override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) {}
                override fun onServiceUnregistered(i: NsdServiceInfo) {}
                override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
            }
            m.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
            nsd = m
            regListener = l
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        running = false
        try { regListener?.let { nsd?.unregisterService(it) } } catch (_: Exception) {}
        try { record?.stop() } catch (_: Exception) {}
        try { record?.release() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        if (prevVolume >= 0) {
            try {
                getSystemService(AudioManager::class.java)
                    .setStreamVolume(AudioManager.STREAM_MUSIC, prevVolume, 0)
            } catch (_: Exception) {}
        }
        super.onDestroy()
    }
}
