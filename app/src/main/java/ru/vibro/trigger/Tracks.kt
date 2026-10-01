package ru.vibro.trigger

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlin.math.max
import kotlin.math.min

/** Поиск своих треков: в выбранной папке и вложенных папках. */
object Tracks {
    private val EXT = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "flac", "wav", "amr", "mka", "3gp")
    private const val MAX = 2000
    private const val DEPTH = 4

    /** Все аудиофайлы папки (дерева SAF). Бросает исключение, если доступа нет. */
    fun scan(ctx: Context, tree: Uri): List<Uri> {
        val out = ArrayList<Uri>()
        walk(ctx, tree, DocumentsContract.getTreeDocumentId(tree), out, 0)
        return out
    }

    private fun walk(ctx: Context, tree: Uri, docId: String, out: MutableList<Uri>, depth: Int) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME
        )
        val dirs = ArrayList<String>()
        ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext() && out.size < MAX) {
                val id = c.getString(0) ?: continue
                val mime = c.getString(1) ?: ""
                val name = c.getString(2) ?: ""
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    dirs += id
                } else if (mime.startsWith("audio/") || name.substringAfterLast('.', "").lowercase() in EXT) {
                    out += DocumentsContract.buildDocumentUriUsingTree(tree, id)
                }
            }
        }
        if (depth < DEPTH) for (d in dirs) if (out.size < MAX) walk(ctx, tree, d, out, depth + 1)
    }

    /** Имя папки, выбранной через ACTION_OPEN_DOCUMENT_TREE. */
    fun treeName(ctx: Context, tree: Uri): String? = try {
        name(ctx, DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)))
    } catch (e: Exception) {
        null
    }

    fun name(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }
}

/** Случайный порядок «колодой»: трек не повторится, пока не сыграют все остальные. */
class ShuffleBag {
    private var key = ""
    private val bag = ArrayDeque<Uri>()
    private var last: Uri? = null

    fun next(all: List<Uri>, key: String): Uri? {
        if (all.isEmpty()) return null
        if (key != this.key) {
            bag.clear()
            this.key = key
        }
        if (bag.isNotEmpty()) {
            // Убираем треки, которых больше нет в папке
            val now = all.toHashSet()
            bag.retainAll { it in now }
        }
        if (bag.isEmpty()) {
            val s = all.shuffled().toMutableList()
            // Новый круг не начинается с того же трека, которым закончился прошлый
            if (s.size > 1 && s[0] == last) {
                val j = 1 + (Math.random() * (s.size - 1)).toInt()
                s[0] = s[j].also { s[j] = s[0] }
            }
            bag.addAll(s)
        }
        return bag.removeFirst().also { last = it }
    }
}

/**
 * Свои треки подряд в случайном порядке с плавной громкостью.
 * Всё, что трогает MediaPlayer, выполняется в главном потоке.
 */
class TrackPlayer(private val ctx: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val bag = ShuffleBag()

    @Volatile private var tracks: List<Uri> = emptyList()
    @Volatile private var key = ""
    @Volatile private var dead = false

    /** Громкость, к которой плавно идём. 0 — затихнуть и освободить плеер. */
    @Volatile var target = 0f

    /** Временно заглушить (не останавливая трек), чтобы послушать тишину. */
    @Volatile var duck = false

    /** Текущая громкость. */
    @Volatile var gain = 0f
        private set

    private var mp: MediaPlayer? = null
    private var prepared = false
    private var ticking = false
    private var fails = 0

    private val tick = object : Runnable {
        override fun run() {
            val want = if (duck) 0f else target
            val step = if (duck) 0.25f else 0.05f // ~0,2 с на заглушение, ~1 с на обычное нарастание/затухание
            gain = if (gain < want) min(want, gain + step) else max(want, gain - step)
            applyVolume()
            if (target == 0f && gain == 0f) {
                close()
                ticking = false
                return
            }
            main.postDelayed(this, 50)
        }
    }

    /** Можно звать из любого потока. */
    fun start(list: List<Uri>, key: String, vol: Float) {
        tracks = list
        this.key = key
        duck = false
        target = vol
        main.post {
            if (dead) return@post
            if (mp == null) {
                fails = 0
                openNext()
            }
            if (!ticking) {
                ticking = true
                main.post(tick)
            }
        }
    }

    fun stop() {
        target = 0f
        duck = false
    }

    /** Только из главного потока. */
    fun shutdown() {
        dead = true
        target = 0f
        main.removeCallbacks(tick)
        ticking = false
        close()
    }

    private fun applyVolume() {
        val p = mp ?: return
        if (!prepared) return
        try {
            p.setVolume(gain, gain)
        } catch (e: Exception) {
        }
    }

    private fun openNext() {
        close()
        if (dead || target == 0f) return
        val list = tracks
        val u = bag.next(list, key) ?: return
        val p = MediaPlayer()
        mp = p
        prepared = false
        try {
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            p.setDataSource(ctx, u)
            p.isLooping = list.size == 1
            p.setOnPreparedListener {
                if (mp !== it) return@setOnPreparedListener
                prepared = true
                applyVolume()
                it.start()
            }
            p.setOnCompletionListener {
                if (mp !== it) return@setOnCompletionListener
                fails = 0
                openNext()
            }
            p.setOnErrorListener { e, what, _ ->
                if (mp === e) failed(u, "код $what")
                true
            }
            p.prepareAsync()
        } catch (e: Exception) {
            failed(u, e.message)
        }
    }

    private fun failed(u: Uri, why: String?) {
        close()
        fails++
        val name = Tracks.name(ctx, u) ?: "трек"
        if (fails >= min(tracks.size, 5)) {
            VibroState.error = "Не удалось воспроизвести «$name»: $why"
            return
        }
        // Битый трек пропускаем и берём следующий
        main.post { if (!dead && mp == null) openNext() }
    }

    private fun close() {
        val p = mp ?: return
        mp = null
        prepared = false
        p.setOnPreparedListener(null)
        p.setOnCompletionListener(null)
        p.setOnErrorListener(null)
        try {
            p.stop()
        } catch (e: Exception) {
        }
        p.release()
    }
}
