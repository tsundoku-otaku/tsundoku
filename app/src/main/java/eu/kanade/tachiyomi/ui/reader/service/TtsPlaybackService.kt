package eu.kanade.tachiyomi.ui.reader.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.util.system.notificationBuilder

class TtsPlaybackService : Service() {

    private var isPaused: Boolean = false
    private var progressPercent: Int = 0
    private var novelTitle: String = "TTS playback"
    private var chapterTitle: String = ""
    private var mangaId: Long = -1L
    private var chapterId: Long = -1L

    override fun onCreate() {
        super.onCreate()

        startForegroundWithNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_TOGGLE_PAUSE -> {
                sendControlBroadcast(COMMAND_TOGGLE_PAUSE)
            }

            ACTION_STOP_PLAYBACK -> {
                sendControlBroadcast(COMMAND_STOP)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_SYNC -> {
                isPaused = intent.getBooleanExtra(EXTRA_IS_PAUSED, false)
                progressPercent = intent.getIntExtra(EXTRA_PROGRESS_PERCENT, 0).coerceIn(0, 100)
                novelTitle = intent.getStringExtra(EXTRA_NOVEL_TITLE).orEmpty().ifBlank { "TTS playback" }
                chapterTitle = intent.getStringExtra(EXTRA_CHAPTER_TITLE).orEmpty()
                mangaId = intent.getLongExtra(EXTRA_MANGA_ID, -1L)
                chapterId = intent.getLongExtra(EXTRA_CHAPTER_ID, -1L)
            }
        }

        startForegroundWithNotification()

        return START_STICKY
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundWithNotification() {

        val toggleIntent = PendingIntent.getService(
            this,
            1001,
            Intent(this, TtsPlaybackService::class.java).setAction(ACTION_TOGGLE_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = PendingIntent.getService(
            this,
            1002,
            Intent(this, TtsPlaybackService::class.java).setAction(ACTION_STOP_PLAYBACK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val openReaderIntent = ReaderActivity.newIntent(
            context = this,
            mangaId = mangaId.takeIf { it > 0L },
            chapterId = chapterId.takeIf { it > 0L },
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

        val openReaderPendingIntent = PendingIntent.getActivity(
            this,
            1003,
            openReaderIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val statusText = if (isPaused) "Paused" else "Reading in background"

        val contentText = if (chapterTitle.isNotBlank()) {
            "$chapterTitle · $statusText"
        } else {
            statusText
        }

        val notification = notificationBuilder(Notifications.CHANNEL_TTS_PLAYBACK) {
            setSmallIcon(R.drawable.ic_mihon)
            setContentTitle(novelTitle)
            setContentText(contentText)
            setContentIntent(openReaderPendingIntent)
            setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            setOngoing(true)
            setOnlyAlertOnce(true)
            setShowWhen(false)
            setProgress(100, progressPercent, false)

            addAction(
                if (isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                if (isPaused) "Resume" else "Pause",
                toggleIntent,
            )

            addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                stopIntent,
            )
        }.build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                Notifications.ID_TTS_PLAYBACK,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(Notifications.ID_TTS_PLAYBACK, notification)
        }
    }

    private fun sendControlBroadcast(command: String) {
        sendBroadcast(
            Intent(ACTION_CONTROL).apply {
                setPackage(packageName)
                putExtra(EXTRA_COMMAND, command)
            },
        )
    }

    companion object {
        private const val ACTION_SYNC =
            "eu.kanade.tachiyomi.ui.reader.service.TtsPlaybackService.SYNC"

        private const val ACTION_STOP_SERVICE =
            "eu.kanade.tachiyomi.ui.reader.service.TtsPlaybackService.STOP_SERVICE"

        private const val ACTION_TOGGLE_PAUSE =
            "eu.kanade.tachiyomi.ui.reader.service.TtsPlaybackService.TOGGLE_PAUSE"

        private const val ACTION_STOP_PLAYBACK =
            "eu.kanade.tachiyomi.ui.reader.service.TtsPlaybackService.STOP_PLAYBACK"

        const val ACTION_CONTROL =
            "eu.kanade.tachiyomi.ui.reader.service.TtsPlaybackService.CONTROL"

        const val EXTRA_COMMAND = "extra_command"

        const val COMMAND_TOGGLE_PAUSE = "toggle_pause"
        const val COMMAND_STOP = "stop"

        private const val EXTRA_IS_PAUSED = "extra_is_paused"
        private const val EXTRA_PROGRESS_PERCENT = "extra_progress_percent"
        private const val EXTRA_NOVEL_TITLE = "extra_novel_title"
        private const val EXTRA_CHAPTER_TITLE = "extra_chapter_title"
        private const val EXTRA_MANGA_ID = "extra_manga_id"
        private const val EXTRA_CHAPTER_ID = "extra_chapter_id"

        fun start(context: Context) {
            syncState(
                context = context,
                isPaused = false,
                progressPercent = 0,
                novelTitle = "TTS playback",
                chapterTitle = "",
                mangaId = -1L,
                chapterId = -1L,
            )
        }

        fun syncState(
            context: Context,
            isPaused: Boolean,
            progressPercent: Int,
            novelTitle: String,
            chapterTitle: String,
            mangaId: Long,
            chapterId: Long,
        ) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, TtsPlaybackService::class.java)
                    .setAction(ACTION_SYNC)
                    .putExtra(EXTRA_IS_PAUSED, isPaused)
                    .putExtra(EXTRA_PROGRESS_PERCENT, progressPercent.coerceIn(0, 100))
                    .putExtra(EXTRA_NOVEL_TITLE, novelTitle)
                    .putExtra(EXTRA_CHAPTER_TITLE, chapterTitle)
                    .putExtra(EXTRA_MANGA_ID, mangaId)
                    .putExtra(EXTRA_CHAPTER_ID, chapterId),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, TtsPlaybackService::class.java)
                    .setAction(ACTION_STOP_SERVICE),
            )
        }
    }
}
