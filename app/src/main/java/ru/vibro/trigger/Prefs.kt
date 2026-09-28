package ru.vibro.trigger

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import java.util.Calendar

object Prefs {
    fun get(ctx: Context): SharedPreferences = ctx.getSharedPreferences("vibro", Context.MODE_PRIVATE)
}

/** Дни недели: подпись и номер бита (0 = воскресенье, как в Calendar). */
val DAYS = listOf("Пн" to 1, "Вт" to 2, "Ср" to 3, "Чт" to 4, "Пт" to 5, "Сб" to 6, "Вс" to 0)

fun fmtTime(m: Int): String = "%02d:%02d".format(m / 60, m % 60)

data class Cfg(
    val thr: Int,
    val attackMs: Long,
    val holdMs: Long,
    val coolMs: Long,
    val freq: Int,
    val vol: Float,
    val mode: String,
    val hp: Boolean,
    val schedOn: Boolean,
    val from: Int,
    val to: Int,
    val days: Int,
    val fileUri: String?
) {
    fun inWindow(c: Calendar = Calendar.getInstance()): Boolean {
        if (!schedOn) return true
        val dow = c.get(Calendar.DAY_OF_WEEK) - 1
        val m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        fun on(d: Int) = (days and (1 shl d)) != 0
        return when {
            from == to -> on(dow)                       // одинаковое время = весь день
            from < to -> on(dow) && m >= from && m < to // обычный интервал, 09:00–14:00
            m >= from -> on(dow)                        // через полночь: вечерняя часть
            m < to -> on((dow + 6) % 7)                 // утренний хвост относится к вчерашнему дню
            else -> false
        }
    }

    companion object {
        fun load(p: SharedPreferences) = Cfg(
            thr = p.getInt("thr", -40),
            attackMs = p.getInt("attack10", 5) * 100L,
            holdMs = p.getInt("hold", 20) * 1000L,
            coolMs = p.getInt("cool", 5) * 1000L,
            freq = p.getInt("freq", 60),
            vol = p.getInt("vol", 80) / 100f,
            mode = p.getString("mode", "sine") ?: "sine",
            hp = p.getBoolean("hp", true),
            schedOn = p.getBoolean("schedOn", false),
            from = p.getInt("from", 9 * 60),
            to = p.getInt("to", 14 * 60),
            days = p.getInt("days", 0x7F),
            fileUri = p.getString("fileUri", null)
        )
    }
}

/** Общее состояние между службой и экраном (один процесс). */
object VibroState {
    @Volatile var running = false
    @Volatile var playing = false
    @Volatile var active = true
    @Volatile var level = -100f
    @Volatile var fires = 0
    @Volatile var playMs = 0L
    @Volatile var playStart = 0L
    @Volatile var lastFire = "—"
    @Volatile var error: String? = null
    @Volatile var testRequest = false

    val hist = FloatArray(200) { -100f }
    @Volatile var histPos = 0

    fun push(v: Float) {
        hist[histPos] = v
        histPos = (histPos + 1) % hist.size
    }

    fun totalPlay(): Long = playMs + (if (playing) SystemClock.elapsedRealtime() - playStart else 0L)
}
