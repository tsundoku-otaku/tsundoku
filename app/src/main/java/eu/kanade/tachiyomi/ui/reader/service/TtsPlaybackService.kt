package eu.kanade.tachiyomi.ui.reader.service

import android.app.ForegroundServiceStartNotAllowedException
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat.MediaStyle
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.util.system.notificationBuilder
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

class TtsPlaybackService : Service() {

    private var isPaused: Boolean = false
    private var progressPercent: Int = 0
    private var paragraphIndex: Int = 0
    private var paragraphCount: Int = 0
    private var novelTitle: String = "TTS playback"
    private var chapterTitle: String = ""
    private var mangaId: Long = -1L
    private var chapterId: Long = -1L

    private lateinit var mediaSession: MediaSessionCompat

    override fun onCreate() {
        super.onCreate()
        mediaSession = MediaSessionCompat(this, "TsundokuTTS").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                // The lockscreen/Bluetooth toggle button fires onPlay()/onPause() depending on
                // which icon PlaybackStateCompat's last-declared state showed; guard against a
                // stray duplicate call (e.g. two quick taps racing the next sync) flipping it back.
                override fun onPlay() {
                    if (isPaused) sendControlBroadcast(COMMAND_TOGGLE_PAUSE)
                }
                override fun onPause() {
                    if (!isPaused) sendControlBroadcast(COMMAND_TOGGLE_PAUSE)
                }
                override fun onSkipToPrevious() = sendControlBroadcast(COMMAND_PREV_PARAGRAPH)
                override fun onSkipToNext() = sendControlBroadcast(COMMAND_NEXT_PARAGRAPH)
                override fun onStop() = handleStopPlayback()
                override fun onSeekTo(pos: Long) {
                    if (paragraphCount <= 0) return
                    val target = (pos / SEEK_UNIT_MS).toInt().coerceIn(0, paragraphCount - 1)
                    sendControlBroadcast(COMMAND_SEEK_PARAGRAPH, target)
                }
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Null intent means a sticky restart by the system; there is no playback
        // state to restore and the app may not be allowed to start a foreground
        // service from the background, so just stop.
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent.action) {
            ACTION_TOGGLE_PAUSE -> {
                sendControlBroadcast(COMMAND_TOGGLE_PAUSE)
            }

            ACTION_PREV_PARAGRAPH -> {
                sendControlBroadcast(COMMAND_PREV_PARAGRAPH)
            }

            ACTION_NEXT_PARAGRAPH -> {
                sendControlBroadcast(COMMAND_NEXT_PARAGRAPH)
            }

            ACTION_STOP_PLAYBACK -> {
                handleStopPlayback()
                return START_NOT_STICKY
            }

            ACTION_SYNC -> {
                isPaused = intent.getBooleanExtra(EXTRA_IS_PAUSED, false)
                progressPercent = intent.getIntExtra(EXTRA_PROGRESS_PERCENT, 0).coerceIn(0, 100)
                paragraphIndex = intent.getIntExtra(EXTRA_PARAGRAPH_INDEX, 0).coerceAtLeast(0)
                paragraphCount = intent.getIntExtra(EXTRA_PARAGRAPH_COUNT, 0).coerceAtLeast(0)
                novelTitle = intent.getStringExtra(EXTRA_NOVEL_TITLE).orEmpty().ifBlank { "TTS playback" }
                chapterTitle = intent.getStringExtra(EXTRA_CHAPTER_TITLE).orEmpty()
                mangaId = intent.getLongExtra(EXTRA_MANGA_ID, -1L)
                chapterId = intent.getLongExtra(EXTRA_CHAPTER_ID, -1L)
            }
        }

        startForegroundWithNotification()

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        mediaSession.isActive = false
        mediaSession.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun handleStopPlayback() {
        sendControlBroadcast(COMMAND_STOP)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

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

        val prevParagraphIntent = PendingIntent.getService(
            this,
            1004,
            Intent(this, TtsPlaybackService::class.java).setAction(ACTION_PREV_PARAGRAPH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val nextParagraphIntent = PendingIntent.getService(
            this,
            1005,
            Intent(this, TtsPlaybackService::class.java).setAction(ACTION_NEXT_PARAGRAPH),
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

        updateMediaSession()

        val statusText = if (isPaused) "Paused" else "Reading in background"
        val paragraphLabel = paragraphProgressLabel()

        val contentText = if (chapterTitle.isNotBlank()) {
            "$chapterTitle · $statusText"
        } else {
            statusText
        }

        val notification = notificationBuilder(Notifications.CHANNEL_TTS_PLAYBACK) {
            setSmallIcon(R.drawable.ic_mihon)
            setContentTitle(novelTitle)
            setContentText(contentText)
            if (paragraphLabel != null) setSubText(paragraphLabel)
            setContentIntent(openReaderPendingIntent)
            setDeleteIntent(stopIntent)
            setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            setOngoing(true)
            setOnlyAlertOnce(true)
            setShowWhen(false)

            addAction(
                R.drawable.ic_skip_previous_24dp,
                "Previous",
                prevParagraphIntent,
            )

            addAction(
                if (isPaused) R.drawable.ic_play_arrow_24dp else R.drawable.ic_pause_24dp,
                if (isPaused) "Resume" else "Pause",
                toggleIntent,
            )

            addAction(
                R.drawable.ic_skip_next_24dp,
                "Next",
                nextParagraphIntent,
            )

            addAction(
                R.drawable.ic_close_24dp,
                "Stop",
                stopIntent,
            )

            setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    // prev / toggle / next in the compact row; Stop stays in the expanded view
                    // (also reachable via swipe-to-dismiss, wired through setDeleteIntent above).
                    .setShowActionsInCompactView(0, 1, 2),
            )
        }.build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    Notifications.ID_TTS_PLAYBACK,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
            } else {
                startForeground(Notifications.ID_TTS_PLAYBACK, notification)
            }
        } catch (e: Exception) {
            // Android 12+ disallows starting a foreground service from the
            // background; stop instead of crashing.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e is ForegroundServiceStartNotAllowedException
            ) {
                logcat(LogPriority.WARN, e) { "Foreground start not allowed for TTS notification" }
                stopSelf()
            } else {
                throw e
            }
        }
    }

    // Feeds the lockscreen/Bluetooth/Android-Auto surfaces, which read PlaybackStateCompat/
    // MediaMetadataCompat rather than the notification's own addAction list. Position/duration are
    // paragraph-index units (one paragraph = SEEK_UNIT_MS), not real audio milliseconds - TTS has
    // no continuous audio position to report - so playback speed is always 0 (static jumps on each
    // sync/seek) rather than 1, which would make the system extrapolate a smoothly-advancing
    // scrubber between syncs that doesn't match how position actually moves.
    private fun updateMediaSession() {
        val positionMs = if (paragraphCount > 0) {
            paragraphIndex.toLong().coerceIn(0, (paragraphCount - 1).toLong()) * SEEK_UNIT_MS
        } else {
            0L
        }
        val durationMs = paragraphCount.coerceAtLeast(1).toLong() * SEEK_UNIT_MS

        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_STOP or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SEEK_TO,
                )
                .setState(
                    if (isPaused) PlaybackStateCompat.STATE_PAUSED else PlaybackStateCompat.STATE_PLAYING,
                    positionMs,
                    0f,
                )
                .build(),
        )

        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, chapterTitle.ifBlank { novelTitle })
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, novelTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, paragraphProgressLabel().orEmpty())
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs)
                .build(),
        )
    }

    private fun paragraphProgressLabel(): String? {
        if (paragraphCount <= 0) return null
        val current = paragraphIndex.coerceIn(0, paragraphCount - 1) + 1
        return "Paragraph $current of $paragraphCount"
    }

    private fun sendControlBroadcast(command: String, seekParagraphIndex: Int? = null) {
        sendBroadcast(
            Intent(ACTION_CONTROL).apply {
                setPackage(packageName)
                putExtra(EXTRA_COMMAND, command)
                if (seekParagraphIndex != null) putExtra(EXTRA_SEEK_PARAGRAPH_INDEX, seekParagraphIndex)
            },
        )
    }

    companion object {
        // One paragraph step = this many synthetic milliseconds of PlaybackStateCompat position/
        // duration - arbitrary but consistent, only used so the system has *a* unit to render a
        // seek bar and to convert a dragged position back to a paragraph index in onSeekTo.
        private const val SEEK_UNIT_MS = 1_000L

        private const val ACTION_SYNC =
            "eu.kanade.tachiyomi.ui.reader.service.TtsPlaybackService.SYNC"

        private const val ACTION_TOGGLE_PAUSE =
            "eu.kanade.tachiyomi.ui.reader.service.TtsPlaybackService.TOGGLE_PAUSE"

        private const val ACTION_PREV_PARAGRAPH =
            "eu.kanade.tachiyomi.ui.reader.service.TtsPlaybackService.PREV_PARAGRAPH"

        private const val ACTION_NEXT_PARAGRAPH =
            "eu.kanade.tachiyomi.ui.reader.service.TtsPlaybackService.NEXT_PARAGRAPH"

        private const val ACTION_STOP_PLAYBACK =
            "eu.kanade.tachiyomi.ui.reader.service.TtsPlaybackService.STOP_PLAYBACK"

        const val ACTION_CONTROL =
            "eu.kanade.tachiyomi.ui.reader.service.TtsPlaybackService.CONTROL"

        const val EXTRA_COMMAND = "extra_command"

        const val COMMAND_TOGGLE_PAUSE = "toggle_pause"
        const val COMMAND_PREV_PARAGRAPH = "prev_paragraph"
        const val COMMAND_NEXT_PARAGRAPH = "next_paragraph"
        const val COMMAND_STOP = "stop"
        const val COMMAND_SEEK_PARAGRAPH = "seek_paragraph"

        const val EXTRA_SEEK_PARAGRAPH_INDEX = "extra_seek_paragraph_index"

        private const val EXTRA_IS_PAUSED = "extra_is_paused"
        private const val EXTRA_PROGRESS_PERCENT = "extra_progress_percent"
        private const val EXTRA_PARAGRAPH_INDEX = "extra_paragraph_index"
        private const val EXTRA_PARAGRAPH_COUNT = "extra_paragraph_count"
        private const val EXTRA_NOVEL_TITLE = "extra_novel_title"
        private const val EXTRA_CHAPTER_TITLE = "extra_chapter_title"
        private const val EXTRA_MANGA_ID = "extra_manga_id"
        private const val EXTRA_CHAPTER_ID = "extra_chapter_id"

        fun syncState(
            context: Context,
            isPaused: Boolean,
            progressPercent: Int,
            paragraphIndex: Int,
            paragraphCount: Int,
            novelTitle: String,
            chapterTitle: String,
            mangaId: Long,
            chapterId: Long,
        ) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, TtsPlaybackService::class.java)
                        .setAction(ACTION_SYNC)
                        .putExtra(EXTRA_IS_PAUSED, isPaused)
                        .putExtra(EXTRA_PROGRESS_PERCENT, progressPercent.coerceIn(0, 100))
                        .putExtra(EXTRA_PARAGRAPH_INDEX, paragraphIndex.coerceAtLeast(0))
                        .putExtra(EXTRA_PARAGRAPH_COUNT, paragraphCount.coerceAtLeast(0))
                        .putExtra(EXTRA_NOVEL_TITLE, novelTitle)
                        .putExtra(EXTRA_CHAPTER_TITLE, chapterTitle)
                        .putExtra(EXTRA_MANGA_ID, mangaId)
                        .putExtra(EXTRA_CHAPTER_ID, chapterId),
                )
            } catch (e: Exception) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    e is ForegroundServiceStartNotAllowedException
                ) {
                    logcat(LogPriority.WARN, e) { "Cannot start TTS service from the background" }
                } else {
                    throw e
                }
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TtsPlaybackService::class.java))
        }
    }
}
