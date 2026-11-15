package com.sinaai.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/** Optional wake-phrase listener. Start it explicitly while the app is visible. */
class WakeWordService : Service() {
    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    override fun onCreate() {
        super.onCreate()
        val channelId = "sina_wake_listener"
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(channelId, "گوش‌به‌زنگ سینا", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channelId) else Notification.Builder(this)
        val notification: Notification = builder.setContentTitle("SINA AI فعال است")
            .setContentText("گوش‌به‌زنگ عبارت سینا یا ممد قلی؛ برای توقف از داخل برنامه خاموش کن.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentIntent(open).build()
        startForeground(702, notification)
        beginListening()
    }

    private fun beginListening() {
        if (listening || !SpeechRecognizer.isRecognitionAvailable(this)) return
        recognizer?.destroy()
        recognizer = if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this))
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this) else SpeechRecognizer.createSpeechRecognizer(this)
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: android.os.Bundle?) {
                listening = false
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.joinToString(" ")?.lowercase(Locale("fa")) ?: ""
                if (text.contains("سینا") || text.contains("ممد قلی") || text.contains("محمد قلی")) {
                    val intent = Intent(this@WakeWordService, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("wake_detected", true)
                    startActivity(intent)
                    stopSelf()
                } else beginListening()
            }
            override fun onError(error: Int) { listening = false; if (error != SpeechRecognizer.ERROR_CLIENT) beginListening() }
            override fun onReadyForSpeech(params: android.os.Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { listening = false }
            override fun onPartialResults(partialResults: android.os.Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.joinToString(" ")?.lowercase(Locale("fa")) ?: ""
                if (text.contains("سینا") || text.contains("ممد قلی") || text.contains("محمد قلی")) {
                    val intent = Intent(this@WakeWordService, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("wake_detected", true)
                    startActivity(intent); stopSelf()
                }
            }
            override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
        })
        val req = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        listening = true
        try { recognizer?.startListening(req) } catch (_: Exception) { listening = false; stopSelf() }
    }

    override fun onDestroy() { listening = false; recognizer?.cancel(); recognizer?.destroy(); recognizer = null; super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
