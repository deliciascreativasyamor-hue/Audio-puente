package com.audiopuente

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.IBinder
import android.os.SystemClock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/** Corre en el celular: recibe el audio de la TV y lo reproduce (sale por tus auriculares Bluetooth). */
class PhonePlayerService : Service() {

    @Volatile private var running = false
    private var thread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val host = intent.getStringExtra(EXTRA_HOST)
        if (host.isNullOrBlank()) {
            stopSelf(); return START_NOT_STICKY
        }
        startForeground(
            2, buildNotif("Escuchando la TV"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
        running = false
        thread?.join(1500)
        running = true
        thread = Thread({ playLoop(host) }, "puente-reproducir").also { it.start() }
        return START_NOT_STICKY
    }

    private fun playLoop(host: String) {
        var socket: DatagramSocket? = null
        var track: AudioTrack? = null
        val hello = "HOLA".toByteArray()
        val bye = "CHAU".toByteArray()
        var addr: InetAddress? = null
        try {
            addr = InetAddress.getByName(host)
            socket = DatagramSocket()
            socket.soTimeout = 500
            socket.receiveBufferSize = 16384 // chico a propósito: si se atrasa, descarta lo viejo

            val minBuf = AudioTrack.getMinBufferSize(
                RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
            )
            val bufSize = maxOf(minBuf, 192 * 80) // ~80 ms de colchón
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setBufferSizeInBytes(bufSize)
                .build()

            val buf = ByteArray(HEADER + PAYLOAD + 64)
            val pkt = DatagramPacket(buf, buf.size)
            var lastHello = 0L
            var started = false
            var count = 0

            while (running) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastHello > 2000) {
                    socket.send(DatagramPacket(hello, hello.size, addr, PORT))
                    lastHello = now
                }
                try {
                    pkt.length = buf.size
                    socket.receive(pkt)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                if (pkt.length <= HEADER) continue
                track.write(buf, HEADER, pkt.length - HEADER)
                if (!started && ++count >= 6) {
                    track.play()
                    started = true
                }
            }
        } catch (_: Exception) {
        } finally {
            try { if (addr != null) socket?.send(DatagramPacket(bye, bye.size, addr, PORT)) } catch (_: Exception) {}
            try { track?.stop() } catch (_: Exception) {}
            try { track?.release() } catch (_: Exception) {}
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }
}
