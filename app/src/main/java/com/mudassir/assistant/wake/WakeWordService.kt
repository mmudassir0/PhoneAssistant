package com.mudassir.assistant.wake

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings as AndroidSettings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.mudassir.assistant.R
import com.mudassir.assistant.Settings
import com.mudassir.assistant.ui.MainActivity
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.util.concurrent.Executors

/**
 * Listens for "Hey Assistant" in the background, fully offline, using Vosk restricted to just
 * that phrase (so it only hears the wake words, nothing else is recognised or stored).
 *
 * The microphone is released whenever the app is open (the app uses its own recogniser).
 */
class WakeWordService : Service(), RecognitionListener {

    private val main = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor()
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speech: SpeechService? = null
    private var lastTrigger = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Hey Assistant", NotificationManager.IMPORTANCE_MIN))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Settings(this).wakeWord = false
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, notification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            )
        } catch (e: Exception) {
            // Android refuses a microphone service started from the background: try again when the app opens.
            Log.w(TAG, "can't start in background", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (model == null) loader.execute { loadModel() } else if (!paused) listen()
        return START_NOT_STICKY
    }

    private fun loadModel() {
        try {
            val m = Model(WakeModel.dir(this).path)
            val r = Recognizer(m, SAMPLE_RATE, "[\"hey assistant\", \"[unk]\"]")
            main.post {
                model = m
                recognizer = r
                if (!paused) listen()
            }
        } catch (e: Exception) {
            Log.e(TAG, "wake model failed", e)
            main.post { stopSelf() }
        }
    }

    private fun listen() {
        val r = recognizer ?: return
        if (speech != null) return
        try {
            speech = SpeechService(r, SAMPLE_RATE).also { it.startListening(this) }
        } catch (e: Exception) {
            Log.w(TAG, "mic busy", e)
            speech = null
            main.postDelayed({ if (!paused) listen() }, 5000)
        }
    }

    private fun release() {
        speech?.let { it.stop(); it.shutdown() }
        speech = null
    }

    // ---- Vosk callbacks (on the main thread) ----

    override fun onPartialResult(hypothesis: String?) = check(hypothesis, "partial")
    override fun onResult(hypothesis: String?) = check(hypothesis, "text")
    override fun onFinalResult(hypothesis: String?) = check(hypothesis, "text")
    override fun onTimeout() {}
    override fun onError(exception: Exception?) {
        Log.w(TAG, "listening error", exception)
        release()
        main.postDelayed({ if (!paused) listen() }, 3000)
    }

    private fun check(json: String?, key: String) {
        val text = runCatching { JSONObject(json ?: "").optString(key) }.getOrDefault("")
        if (!text.contains("hey assistant")) return
        val now = System.currentTimeMillis()
        if (now - lastTrigger < 3000) return
        lastTrigger = now
        recognizer?.reset()
        triggered()
    }

    private fun triggered() {
        release() // free the microphone for the app
        val open = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_LISTEN, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        if (AndroidSettings.canDrawOverlays(this)) {
            startActivity(open) // allowed from the background thanks to "display over other apps"
        } else {
            // Without that permission Android blocks opening screens from the background: offer a tap instead.
            getSystemService(NotificationManager::class.java).notify(
                NOTIF_ID + 1,
                NotificationCompat.Builder(this, com.mudassir.assistant.App.CH_REMINDERS)
                    .setSmallIcon(R.drawable.ic_mic)
                    .setContentTitle("I'm listening")
                    .setContentText("Tap to talk")
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .setTimeoutAfter(15_000)
                    .setContentIntent(PendingIntent.getActivity(this, 7, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                    .build()
            )
            main.postDelayed({ if (!paused) listen() }, 4000)
        }
    }

    private fun notification(): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Say \"Hey Assistant\"")
            .setContentText("Listening offline for the wake word")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, "Turn off", stop)
            .build()
    }

    override fun onDestroy() {
        release()
        loader.execute {
            recognizer?.close()
            model?.close()
        }
        loader.shutdown()
        instance = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WakeWord"
        private const val CHANNEL = "wake"
        private const val NOTIF_ID = 3001
        private const val SAMPLE_RATE = 16000f
        private const val ACTION_STOP = "com.mudassir.assistant.WAKE_STOP"

        private var instance: WakeWordService? = null
        /** True while the app is open (it uses the microphone itself). */
        private var paused = false

        /** Starts listening if the wake word is on and its model is downloaded. Call from the app (foreground). */
        fun start(ctx: Context) {
            if (!Settings(ctx).wakeWord || WakeModel.state.value != WakeModel.State.Ready) return
            runCatching { ctx.startForegroundService(Intent(ctx, WakeWordService::class.java)) }
        }

        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, WakeWordService::class.java))

        /** The app is open: give it the microphone. */
        fun pause() {
            paused = true
            instance?.release()
        }

        /**
         * The app went to the background: listen again. (The service itself is started while the
         * app is open, because Android doesn't allow starting a microphone service from the background.)
         */
        fun resume() {
            paused = false
            val s = instance ?: return
            s.main.postDelayed({ if (!paused) s.listen() }, 500)
        }
    }
}
