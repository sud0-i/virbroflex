package ru.vibro.trigger

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/** Генератор синуса с плавным нарастанием/затуханием. Режимы: sine, pulse, sweep. */
class ToneGen {
    @Volatile var freq = 60.0
    @Volatile var vol = 0.8f
    @Volatile var mode = "sine"
    @Volatile private var on = false
    private var alive = false
    private var thread: Thread? = null
    private val lock = Any()

    fun start() {
        synchronized(lock) {
            on = true
            if (!alive) {
                alive = true
                thread = Thread({ render() }, "vibro-tone").also { it.start() }
            }
        }
    }

    fun stop() {
        on = false
    }

    fun shutdown() {
        on = false
        thread?.join(1500)
    }

    private fun render() {
        val fs = 44100
        var track: AudioTrack? = null
        try {
            val minBuf = AudioTrack.getMinBufferSize(fs, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val tr = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(fs)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(max(minBuf, 8192))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track = tr
            tr.play()

            val buf = ShortArray(1024)
            var phase = 0.0
            var gain = 0.0
            var t = 0L
            val ramp = 1.0 / (0.3 * fs) // 0,3 с на полное нарастание

            while (true) {
                val target = if (on) vol.toDouble() else 0.0
                val m = mode
                val f0 = freq
                for (i in buf.indices) {
                    val d = target - gain
                    gain += if (d > ramp) ramp else if (d < -ramp) -ramp else d
                    val sec = t.toDouble() / fs
                    val f = if (m == "sweep") f0 + 8.0 * sin(2 * PI * 0.15 * sec) else f0
                    phase += 2 * PI * f / fs
                    if (phase > 2 * PI) phase -= 2 * PI
                    val env = if (m == "pulse") {
                        val c = sec % 1.7
                        when {
                            c < 0.03 -> c / 0.03
                            c < 1.0 -> 1.0
                            c < 1.03 -> 1.0 - (c - 1.0) / 0.03
                            else -> 0.0
                        }
                    } else 1.0
                    buf[i] = (sin(phase) * gain * env * 32000.0).toInt().toShort()
                    t++
                }
                tr.write(buf, 0, buf.size)
                var exit = false
                synchronized(lock) {
                    if (!on && gain <= 0.0) {
                        alive = false
                        exit = true
                    }
                }
                if (exit) break
            }
        } catch (e: Exception) {
            VibroState.error = "Ошибка воспроизведения: ${e.message}"
            synchronized(lock) { alive = false }
        } finally {
            try {
                track?.stop()
            } catch (e: Exception) {
            }
            track?.release()
        }
    }
}

/** Фильтр высоких частот (RBJ biquad), чтобы микрофон не слышал саму колонку. */
class Biquad {
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    fun highpass(f: Double, fs: Double, q: Double = 0.707) {
        val w0 = 2 * PI * f / fs
        val cs = kotlin.math.cos(w0)
        val alpha = sin(w0) / (2 * q)
        val a0 = 1 + alpha
        b0 = (1 + cs) / 2 / a0
        b1 = -(1 + cs) / a0
        b2 = (1 + cs) / 2 / a0
        a1 = -2 * cs / a0
        a2 = (1 - alpha) / a0
    }

    fun process(x: Double): Double {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x
        y2 = y1; y1 = y
        return y
    }
}
