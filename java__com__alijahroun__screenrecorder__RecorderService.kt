package com.alijahroun.screenrecorder

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Foreground service (type mediaProjection, + microphone when audio is requested) that owns the
 * MediaProjection and the recording engine. It is controlled only through its notification.
 */
class RecorderService : Service() {

    companion object {
        const val ACTION_START = "com.alijahroun.screenrecorder.action.START"
        const val ACTION_STOP = "com.alijahroun.screenrecorder.action.STOP"
        const val ACTION_PAUSE = "com.alijahroun.screenrecorder.action.PAUSE"
        const val ACTION_RESUME = "com.alijahroun.screenrecorder.action.RESUME"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_NO_AUDIO = "no_audio"

        private const val CHANNEL_RECORDING = "recording"
        private const val CHANNEL_SAVED = "saved"
        private const val NOTIF_RECORDING = 1
        private const val NOTIF_SAVED = 2
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()

    private var projection: MediaProjection? = null
    private var engine: ScreenRecorderEngine? = null
    private var outputUri: Uri? = null
    private var outputPfd: ParcelFileDescriptor? = null

    private var infoText = ""
    private var accumulatedMs = 0L
    private var segmentStartMs = 0L

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // Stopped by the system / user (e.g. status-bar chip): keep what we have.
            mainHandler.post { finishRecording() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(intent)
            ACTION_STOP -> if (engine != null) finishRecording() else stopSelf()
            ACTION_PAUSE -> pauseRecording()
            ACTION_RESUME -> resumeRecording()
            else -> if (engine == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------- start

    private fun startRecording(intent: Intent) {
        if (engine != null || RecorderStatus.phase.value != RecorderStatus.Phase.IDLE) return

        val settings = SettingsStore(this).load()
        val micGranted = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        var audioMode = if (intent.getBooleanExtra(EXTRA_NO_AUDIO, false)) {
            AudioMode.NONE
        } else {
            settings.audio
        }
        if (audioMode != AudioMode.NONE && !micGranted) audioMode = AudioMode.NONE

        createChannels()
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (audioMode != AudioMode.NONE) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        try {
            ServiceCompat.startForeground(this, NOTIF_RECORDING, buildNotification(starting = true), type)
        } catch (e: Exception) {
            failAndStop("Could not start the recording service: ${e.message}", null)
            return
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
        if (resultCode != Activity.RESULT_OK || data == null) {
            failAndStop("Screen capture permission was not granted", null)
            return
        }

        var eng: ScreenRecorderEngine? = null
        try {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val proj = mpm.getMediaProjection(resultCode, data)
            // Required on Android 14+: register before creating a VirtualDisplay.
            proj.registerCallback(projectionCallback, mainHandler)
            projection = proj

            val caps = CaptureProfile.probe(this)
                ?: throw IOException("No H.264 encoder found on this device")
            val config = CaptureProfile.resolve(settings, caps)

            val (uri, pfd) = createOutput()
            outputUri = uri
            outputPfd = pfd

            eng = ScreenRecorderEngine(this, proj, caps, config, audioMode, pfd)
            eng.start()
            engine = eng

            val c = eng.config
            infoText = "${c.width}×${c.height} • ${c.fps} fps" + if (eng.hasAudio) " • audio" else ""
            accumulatedMs = 0L
            segmentStartMs = SystemClock.elapsedRealtime()
            RecorderStatus.phase.value = RecorderStatus.Phase.RECORDING
            notifyUpdate()
            eng.warnings.forEach { RecorderStatus.events.tryEmit(it) }
        } catch (e: Exception) {
            failAndStop("Could not start recording: ${e.message ?: e.javaClass.simpleName}", eng)
        }
    }

    private fun createOutput(): Pair<Uri, ParcelFileDescriptor> {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "ScreenRecord_$stamp.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ScreenRecordings")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = contentResolver.insert(collection, values)
            ?: throw IOException("Could not create the output file")
        val pfd: ParcelFileDescriptor? = try {
            contentResolver.openFileDescriptor(uri, "rw")
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            throw e
        }
        if (pfd == null) {
            contentResolver.delete(uri, null, null)
            throw IOException("Could not open the output file")
        }
        return uri to pfd
    }

    private fun publish(uri: Uri) {
        val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
        contentResolver.update(uri, values, null, null)
    }

    // ---------------------------------------------------------------- pause / resume

    private fun pauseRecording() {
        val eng = engine ?: return
        if (RecorderStatus.phase.value != RecorderStatus.Phase.RECORDING) return
        eng.pause()
        accumulatedMs += SystemClock.elapsedRealtime() - segmentStartMs
        RecorderStatus.phase.value = RecorderStatus.Phase.PAUSED
        notifyUpdate()
    }

    private fun resumeRecording() {
        val eng = engine ?: return
        if (RecorderStatus.phase.value != RecorderStatus.Phase.PAUSED) return
        eng.resume()
        segmentStartMs = SystemClock.elapsedRealtime()
        RecorderStatus.phase.value = RecorderStatus.Phase.RECORDING
        notifyUpdate()
    }

    // ---------------------------------------------------------------- stop / save

    private fun finishRecording() {
        val eng = engine ?: return
        engine = null
        val uri = outputUri
        val pfd = outputPfd
        val proj = projection
        outputUri = null
        outputPfd = null
        projection = null

        RecorderStatus.phase.value = RecorderStatus.Phase.SAVING
        notifyUpdate()

        worker.execute {
            val ok = try {
                eng.stop()
            } catch (e: Exception) {
                false
            }
            try {
                pfd?.close()
            } catch (_: Exception) {
            }
            releaseProjection(proj)

            if (uri != null) {
                try {
                    if (ok) publish(uri) else contentResolver.delete(uri, null, null)
                } catch (_: Exception) {
                }
            }
            RecorderStatus.phase.value = RecorderStatus.Phase.IDLE
            if (ok && uri != null) {
                RecorderStatus.events.tryEmit("Saved to Movies/ScreenRecordings")
                showSavedNotification(uri)
            } else {
                RecorderStatus.events.tryEmit("Recording was too short or failed - nothing saved")
            }
            mainHandler.post {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun releaseProjection(proj: MediaProjection?) {
        try {
            proj?.unregisterCallback(projectionCallback)
        } catch (_: Exception) {
        }
        try {
            proj?.stop()
        } catch (_: Exception) {
        }
    }

    private fun failAndStop(message: String, eng: ScreenRecorderEngine?) {
        try {
            eng?.abort()
        } catch (_: Exception) {
        }
        try {
            outputPfd?.close()
        } catch (_: Exception) {
        }
        outputUri?.let {
            try {
                contentResolver.delete(it, null, null)
            } catch (_: Exception) {
            }
        }
        releaseProjection(projection)
        engine = null
        outputUri = null
        outputPfd = null
        projection = null
        RecorderStatus.phase.value = RecorderStatus.Phase.IDLE
        RecorderStatus.events.tryEmit(message)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        // Best effort: if the system destroys us mid-recording, still finalise the MP4.
        val eng = engine
        if (eng != null) {
            engine = null
            val ok = try {
                eng.stop()
            } catch (e: Exception) {
                false
            }
            try {
                outputPfd?.close()
            } catch (_: Exception) {
            }
            outputUri?.let {
                try {
                    if (ok) publish(it) else contentResolver.delete(it, null, null)
                } catch (_: Exception) {
                }
            }
            releaseProjection(projection)
            projection = null
            RecorderStatus.phase.value = RecorderStatus.Phase.IDLE
        }
        worker.shutdown()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- notifications

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RECORDING,
                getString(R.string.channel_recording),
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SAVED,
                getString(R.string.channel_saved),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
    }

    private fun actionIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, RecorderService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun buildNotification(starting: Boolean = false): Notification {
        val phase = RecorderStatus.phase.value
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_record)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp)

        when {
            starting -> builder
                .setContentTitle(getString(R.string.notif_recording))
                .setContentText("…")

            phase == RecorderStatus.Phase.SAVING -> builder
                .setContentTitle(getString(R.string.saving))
                .setContentText(infoText)

            phase == RecorderStatus.Phase.PAUSED -> {
                val seconds = accumulatedMs / 1000
                builder
                    .setContentTitle(getString(R.string.notif_paused))
                    .setContentText("%02d:%02d • %s".format(seconds / 60, seconds % 60, infoText))
                    .setUsesChronometer(false)
                    .setShowWhen(false)
                    .addAction(R.drawable.ic_stop, getString(R.string.notif_stop), actionIntent(ACTION_STOP, 1))
                    .addAction(R.drawable.ic_play, getString(R.string.notif_resume), actionIntent(ACTION_RESUME, 2))
            }

            else -> {
                val recordedMs = accumulatedMs + (SystemClock.elapsedRealtime() - segmentStartMs)
                builder
                    .setContentTitle(getString(R.string.notif_recording))
                    .setContentText(infoText)
                    .setUsesChronometer(true)
                    .setShowWhen(true)
                    .setWhen(System.currentTimeMillis() - recordedMs)
                    .addAction(R.drawable.ic_stop, getString(R.string.notif_stop), actionIntent(ACTION_STOP, 1))
                    .addAction(R.drawable.ic_pause, getString(R.string.notif_pause), actionIntent(ACTION_PAUSE, 3))
            }
        }
        return builder.build()
    }

    private fun notifyUpdate() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_RECORDING, buildNotification())
    }

    private fun showSavedNotification(uri: Uri) {
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            this, 10, view, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(this, CHANNEL_SAVED)
            .setSmallIcon(R.drawable.ic_record)
            .setContentTitle(getString(R.string.notif_saved))
            .setContentText(getString(R.string.notif_saved_text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_SAVED, n)
    }
}
