package ru.vibro.trigger

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
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
import kotlin.math.sqrt

/** Фоновая служба: слушает микрофон и включает звук. */
class VibroService : Service() {
    @Volatile private var stopFlag = false
    private var worker: Thread? = null
    private val tone = ToneGen()
    private val main = Handler(Looper.getMainLooper())
    private var wake: PowerManager.WakeLock? = null

    private val player by lazy { TrackPlayer(this) }

    // Состояние логики (только поток worker)
    private var playingMode = ""
    private var usingTone = false
    private var lastStop = -1_000_000L
    private var filterUntil = 0L

    // Проверка тишины во время своих треков
    private var lastCheck = 0L
    private var checkStart = 0L
    private var muteAt = 0L
    private var checkLoud = 0

    // Кэш списка треков папки
    private var scanKey = ""
    private var scanAt = -1_000_000L
    private var scanList: List<Uri> = emptyList()

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
        player.shutdown()
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
                    tone.freq = cfg.freq.toDouble()
                    tone.vol = cfg.vol
                    tone.mode = cfg.mode
                    if (VibroState.playing && !usingTone) player.target = cfg.vol
                }

                // Пока звучит тон (и секунду после), низ отрезаем, чтобы колонка не продлевала сама себя.
                // В остальное время слушаем всё: топот и удары как раз ниже 200 Гц.
                val hf = if ((VibroState.playing && usingTone) || now < filterUntil) 200.0 else 10.0
                if (hf != hpFreq) {
                    hpf.highpass(hf, fs.toDouble())
                    hpf.reset()
                    hpFreq = hf
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
                // Свои треки микрофон слышит сам — шум ловим только в паузах-проверках
                val tracksOn = VibroState.playing && !usingTone
                if (sustained && !tracksOn) lastLoud = now

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
                if (VibroState.playing && !testing) {
                    if (usingTone) {
                        if (now - lastLoud > cfg.holdMs) stopPlay(now)
                    } else {
                        lastLoud = checkStep(cfg, now, smooth, lastLoud)
                        if (!VibroState.playing) aboveSince = 0L
                    }
                }
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

    /**
     * Своих треков микрофон не отличает от соседей, поэтому раз в CHECK_EVERY (и перед остановкой)
     * колонка на секунду затихает, и мы слушаем тишину. Шумно — играем дальше, тихо и время вышло — стоп.
     * Возвращает новое время последнего шума.
     */
    private fun checkStep(cfg: Cfg, now: Long, level: Float, lastLoud: Long): Long {
        if (checkStart == 0L) {
            if (now - lastLoud > cfg.holdMs || now - lastCheck > CHECK_EVERY) {
                checkStart = now
                muteAt = 0L
                checkLoud = 0
                player.duck = true
            }
            return lastLoud
        }
        if (muteAt == 0L && (player.gain == 0f || now - checkStart > 1500)) muteAt = now
        if (muteAt == 0L || now - muteAt < CHECK_SETTLE) return lastLoud
        if (level > cfg.thr) checkLoud++
        if (now - muteAt < CHECK_SETTLE + CHECK_LISTEN) return lastLoud

        // Проверка окончена: ~3 блока (0,15 с) выше порога — значит, шумят
        val loud = if (checkLoud >= 3) now else lastLoud
        checkStart = 0L
        lastCheck = now
        if (now - loud > cfg.holdMs) stopPlay(now) else player.duck = false
        return loud
    }

    private fun startPlay(cfg: Cfg, now: Long) {
        VibroState.error = null
        VibroState.playing = true
        VibroState.playStart = now
        playingMode = cfg.mode
        lastCheck = now
        checkStart = 0L
        usingTone = cfg.mode != "file" || !startTracks(cfg, now)
        if (usingTone) tone.start()
    }

    private fun stopPlay(now: Long) {
        VibroState.playMs += now - VibroState.playStart
        VibroState.playing = false
        lastStop = now
        if (usingTone) filterUntil = now + 1000
        checkStart = 0L
        tone.stop()
        player.stop()
    }

    /** Запускает свои треки. false — играть нечего, вместо них будет тон. */
    private fun startTracks(cfg: Cfg, now: Long): Boolean {
        val dir = cfg.dirUri
        val list: List<Uri>
        val key: String
        if (cfg.trackSrc == "dir" && dir != null) {
            key = dir
            if (key != scanKey || now - scanAt > 60_000) {
                try {
                    scanList = Tracks.scan(this, Uri.parse(dir))
                } catch (e: Exception) {
                    VibroState.error = "Нет доступа к папке, выберите её заново. Пока играет обычный тон."
                    return false
                }
                scanKey = key
                scanAt = now
            }
            list = scanList
            if (list.isEmpty()) {
                VibroState.error = "В папке нет аудиофайлов. Пока играет обычный тон."
                return false
            }
        } else {
            val f = cfg.fileUri
            if (f == null) {
                VibroState.error = "Треки не выбраны. Пока играет обычный тон."
                return false
            }
            key = f
            list = listOf(Uri.parse(f))
        }
        player.start(list, key, cfg.vol)
        return true
    }

    companion object {
        const val CHANNEL = "listen"

        const val CHECK_EVERY = 15_000L  // как часто проверять тишину во время треков
        const val CHECK_SETTLE = 300L    // после заглушения ждём, пока утихнет эхо
        const val CHECK_LISTEN = 1000L   // сколько слушать
    }
}
