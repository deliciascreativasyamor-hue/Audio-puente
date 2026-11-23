package com.audiopuente

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import java.net.Inet4Address
import java.net.NetworkInterface

const val PORT = 49222
const val RATE = 48000
const val HEADER = 4          // número de secuencia (Int)
const val PAYLOAD = 1280      // 320 frames estéreo de 16 bits = ~6,7 ms de audio
const val SERVICE_TYPE = "_audiopuente._udp."

const val ACTION_STOP = "com.audiopuente.STOP"
const val EXTRA_CODE = "code"
const val EXTRA_DATA = "data"
const val EXTRA_MUTE = "mute"
const val EXTRA_HOST = "host"

private const val CHANNEL_ID = "audiopuente"

fun Context.buildNotif(text: String): Notification {
    val nm = getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, "Audio Puente", NotificationManager.IMPORTANCE_LOW)
    )
    return Notification.Builder(this, CHANNEL_ID)
        .setContentTitle("Audio Puente")
        .setContentText(text)
        .setSmallIcon(android.R.drawable.ic_media_play)
        .setOngoing(true)
        .build()
}

fun localIp(): String? {
    try {
        for (ni in NetworkInterface.getNetworkInterfaces()) {
            if (!ni.isUp || ni.isLoopback) continue
            for (a in ni.inetAddresses) {
                if (a is Inet4Address && a.isSiteLocalAddress) return a.hostAddress
            }
        }
    } catch (_: Exception) {
    }
    return null
}
