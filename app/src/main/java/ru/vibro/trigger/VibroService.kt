package ru.vibro.trigger

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Фоновая служба: слушает микрофон и включает звук. */
class VibroService : Service() {
    @Volatile private var stopFlag = false
    private var worker: Thread? = null
    private val tone = ToneGen()
    private val main = Handler(Looper.getMainLooper())
    private var wake: PowerManager.WakeLock? = null

    // Файловый режим
    @Volatile private var player: MediaPlayer? = null
    @Volatile private var fileStarting = false
    @Volatile private var fileTarget = 0f
    private var fileGain = 0f

    // Состояние логики (только поток worker)
    private var playingMode = ""
    private var lastStop = -1_000_000L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (worker == null) {
            try {
                goForeground()
            } catch (e: Exception) {
                VibroState.error = "Система не дала запустить работу в фоне: ${e.message}"
                stopSelf()
                return START_NOT_STICKY
            }
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vibro:listen").also { it.acquire() }
            stopFlag = false
            VibroState.running = true
            worker = Thread({ loop() }, "vibro-listen").also { it.start() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopFlag = true
        worker?.join(2000)
        worker = null
        tone.shutdown()
        player?.let {
            try {
                it.stop()
            } catch (e: Exception) {
            }
            it.release()
        }
        player = null
        wake?.let { if (it.isHeld) it.release() }
        VibroState.running = false
        VibroState.playing = false
        VibroState.level = -100f
        super.onDestroy()
    }

    private fun goForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Прослушивание", NotificationManager.IMPORTANCE_LOW))
        }
        val open = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        val n = b.setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Вибро-триггер слушает")
            .setContentText("Нажмите, чтобы открыть настройки")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, n)
        }
    }

    @SuppressLint("MissingPermission")
    private fun openRecorder(fs: Int, size: Int): AudioRecord? {
        // UNPROCESSED и VOICE_RECOGNITION обычно без автоусиления — уровень честнее
        val sources = intArrayOf(
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC
        )
        for (src in sources) {
            try {
                val r = AudioRecord(src, fs, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
                if (r.state == AudioRecord.STATE_INITIALIZED) return r
                r.release()
            } catch (e: Exception) {
            }
        }
        return null
    }

    private fun fail(msg: String) {
        VibroState.error = msg
        main.post { stopSelf() }
    }

    private fun loop() {
        val fs = 44100
        val minBuf = AudioRecord.getMinBufferSize(fs, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = openRecorder(fs, max(minBuf, 16384))
        if (rec == null) {
            fail("Не удалось открыть микрофон. Возможно, он занят другим приложением.")
            return
        }
        try {
            rec.startRecording()
        } catch (e: Exception) {
            rec.release()
            fail("Не удалось начать запись: ${e.message}")
            return
        }

        val prefs = Prefs.get(this)
        var cfg = Cfg.load(prefs)
        var cfgAt = 0L
        val hpf = Biquad()
        var hpFreq = -1.0
        val buf = ShortArray(2048) // ~46 мс
        var smooth = -100f
        var aboveSince = 0L
        var lastLoud = 0L
        var testUntil = 0L
        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

        try {
            while (!stopFlag) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) {
                    Thread.sleep(50)
                    continue
                }
                val now = SystemClock.elapsedRealtime()

                if (now - cfgAt > 500) {
                    cfg = Cfg.load(prefs)
                    cfgAt = now
                    val hf = if (cfg.hp) 200.0 else 10.0
                    if (hf != hpFreq) {
                        hpf.highpass(hf, fs.toDouble())
                        hpFreq = hf
                    }
                    tone.freq = cfg.freq.toDouble()
                    tone.vol = cfg.vol
                    tone.mode = cfg.mode
                    if (VibroState.playing && playingMode == "file") fileTarget = cfg.vol
                }

                // Уровень после фильтра
                var sum = 0.0
                for (i in 0 until n) {
                    val x = hpf.process(buf[i] / 32768.0)
                    sum += x * x
                }
                val db = (20.0 * log10(sqrt(sum / n) + 1e-9)).toFloat()
                smooth = smooth * 0.6f + db * 0.4f
                VibroState.level = smooth
                VibroState.push(smooth)

                // Решение
                if (smooth > cfg.thr) {
                    if (aboveSince == 0L) aboveSince = now
                } else {
                    aboveSince = 0L
                }
                val sustained = aboveSince != 0L && now - aboveSince >= cfg.attackMs
                // Аудиофайл микрофон слышит сам — во время файла шум не продлевает звук
                val selfHearing = VibroState.playing && playingMode == "file"
                if (sustained && !selfHearing) lastLoud = now

                if (VibroState.testRequest) {
                    VibroState.testRequest = false
                    testUntil = now + 3000
                    if (!VibroState.playing) startPlay(cfg, now)
                }
                val testing = now < testUntil
                val active = cfg.inWindow()
                VibroState.active = active

                if (VibroState.playing && playingMode != cfg.mode) {
                    stopPlay(now)
                    startPlay(cfg, now)
                }

                if (!active) {
                    if (VibroState.playing && !testing) stopPlay(now)
                    aboveSince = 0L
                } else if (!VibroState.playing && sustained && now - lastStop >= cfg.coolMs) {
                    VibroState.fires++
                    VibroState.lastFire = timeFmt.format(Date())
                    lastLoud = now
                    startPlay(cfg, now)
                }
                if (VibroState.playing && !testing && now - lastLoud > cfg.holdMs) stopPlay(now)

                stepFileFade()
            }
        } catch (e: InterruptedException) {
        } finally {
            if (VibroState.playing) stopPlay(SystemClock.elapsedRealtime())
            try {
                rec.stop()
            } catch (e: Exception) {
            }
            rec.release()
            VibroState.running = false
            VibroState.level = -100f
        }
    }

    private fun startPlay(cfg: Cfg, now: Long) {
        VibroState.playing = true
        VibroState.playStart = now
        playingMode = cfg.mode
        if (cfg.mode == "file") startFile(cfg) else tone.start()
    }

    private fun stopPlay(now: Long) {
        VibroState.playMs += now - VibroState.playStart
        VibroState.playing = false
        lastStop = now
        tone.stop()
        fileTarget = 0f
    }

    private fun startFile(cfg: Cfg) {
        val u = cfg.fileUri
        if (u == null) {
            VibroState.error = "Файл не выбран — играет обычный тон."
            tone.start()
            return
        }
        fileTarget = cfg.vol
        if (player != null || fileStarting) return
        fileStarting = true
        main.post {
            try {
                val mp = MediaPlayer()
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                mp.setDataSource(this, Uri.parse(u))
                mp.isLooping = true
                mp.prepare()
                mp.setVolume(0f, 0f)
                mp.start()
                fileGain = 0f
                player = mp
            } catch (e: Exception) {
                VibroState.error = "Не удалось воспроизвести файл: ${e.message}"
            } finally {
                fileStarting = false
            }
        }
    }

    /** Плавная громкость файла, ~1 с на полное нарастание/затухание. */
    private fun stepFileFade() {
        val mp = player ?: return
        val step = 0.05f
        fileGain = if (fileGain < fileTarget) min(fileTarget, fileGain + step) else max(fileTarget, fileGain - step)
        try {
            mp.setVolume(fileGain, fileGain)
        } catch (e: Exception) {
        }
        if (fileTarget == 0f && fileGain == 0f) {
            player = null
            main.post {
                try {
                    mp.stop()
                } catch (e: Exception) {
                }
                mp.release()
            }
        }
    }

    companion object {
        const val CHANNEL = "listen"
    }
}
