package ru.vibro.trigger

import android.Manifest
import android.app.Activity
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.ToggleButton
import kotlin.math.roundToInt

private val BG = 0xFF0F1412.toInt()
private val PANEL = 0xFF161D1A.toInt()
private val LINE = 0xFF26312C.toInt()
private val TEXT = 0xFFE3EBE6.toInt()
private val MUTED = 0xFF8A9A92.toInt()
private val ACCENT = 0xFFE8B23A.toInt()
private val LIVE = 0xFFE2553F.toInt()
private val OKC = 0xFF4FBF8A.toInt()
private val DARK = 0xFF1A1405.toInt()

class MainActivity : Activity() {
    private val prefs by lazy { Prefs.get(this) }
    private val ui = Handler(Looper.getMainLooper())
    private val bars = HashMap<String, SeekBar>()
    private var uiErr: String? = null

    private lateinit var status: TextView
    private lateinit var levelText: TextView
    private lateinit var levelView: LevelView
    private lateinit var goBtn: Button
    private lateinit var goBg: GradientDrawable
    private lateinit var errText: TextView
    private lateinit var settingsBtn: Button
    private lateinit var statsText: TextView
    private lateinit var schedInfo: TextView
    private lateinit var trackInfo: TextView

    private val match = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(BG)
            addView(root)
        }
        setContentView(scroll)

        // Заголовок и статус
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(tv("Вибро-триггер", 22f, TEXT, true), LinearLayout.LayoutParams(0, wrap, 1f))
        status = tv("СТОП", 12f, MUTED, true).apply { letterSpacing = 0.08f }
        header.addView(status)
        root.addView(header)

        // Уровень шума
        val meter = panel(root, "Уровень шума")
        levelText = tv("— dBFS", 32f, TEXT).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) }
        meter.addView(levelText, lp(6))
        levelView = LevelView(this) { prefs.getInt("thr", -40) }
        meter.addView(levelView, LinearLayout.LayoutParams(match, dp(90)).apply { topMargin = dp(8) })
        meter.addView(tv("Жёлтая линия — порог. Красное — шум выше порога.", 12f, MUTED), lp(8))

        // Главная кнопка
        goBtn = button("Начать слушать", ACCENT, DARK) { toggle() }
        goBtn.textSize = 18f
        goBg = goBtn.background as GradientDrawable
        root.addView(goBtn, lp(16))
        errText = tv("", 14f, LIVE).apply { visibility = View.GONE }
        root.addView(errText, lp(8))
        settingsBtn = button("Открыть настройки приложения") { openAppSettings() }.apply { visibility = View.GONE }
        root.addView(settingsBtn, lp(8))

        // Звук
        val sound = panel(root, "Что включать")
        val fileRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val cur = prefs.getString("mode", "sine")
        val rg = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val modes = listOf(
            "sine" to "Ровный тон",
            "pulse" to "Импульсы (1 с звук, 0,7 с пауза)",
            "sweep" to "Плавание частоты ±8 Гц",
            "file" to "Свои треки (папка вперемешку или один файл)"
        )
        for ((key, name) in modes) {
            val rb = RadioButton(this).apply {
                text = name
                tag = key
                id = View.generateViewId()
                setTextColor(TEXT)
                buttonTintList = ColorStateList.valueOf(ACCENT)
            }
            rg.addView(rb)
            if (key == cur) rg.check(rb.id)
        }
        rg.setOnCheckedChangeListener { g, checkedId ->
            val key = g.findViewById<RadioButton>(checkedId)?.tag as? String ?: return@setOnCheckedChangeListener
            prefs.edit().putString("mode", key).apply()
            fileRow.visibility = if (key == "file") View.VISIBLE else View.GONE
        }
        sound.addView(rg, lp(6))

        val pickRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pickRow.addView(
            button("Выбрать папку") { pickFolder() },
            LinearLayout.LayoutParams(0, wrap, 1f).apply { rightMargin = dp(6) }
        )
        pickRow.addView(
            button("Выбрать файл") { pickFile() },
            LinearLayout.LayoutParams(0, wrap, 1f).apply { leftMargin = dp(6) }
        )
        fileRow.addView(pickRow)
        trackInfo = tv("", 13f, MUTED)
        fileRow.addView(trackInfo, lp(8))
        updateTrackInfo()
        fileRow.visibility = if (cur == "file") View.VISIBLE else View.GONE
        sound.addView(fileRow, lp(8))

        slider(sound, "Частота", "freq", 20, 200, 60) { "$it Гц" }
        slider(sound, "Громкость", "vol", 0, 100, 80) { "$it%" }
        sound.addView(tv("Громкость мультимедиа на телефоне тоже должна быть поднята.", 12f, MUTED), lp(4))

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnRow.addView(
            button("Проверить звук") { test() },
            LinearLayout.LayoutParams(0, wrap, 1f).apply { rightMargin = dp(6) }
        )
        lateinit var calib: Button
        calib = button("Калибровка порога") { calibrate(calib) }
        btnRow.addView(calib, LinearLayout.LayoutParams(0, wrap, 1f).apply { leftMargin = dp(6) })
        sound.addView(btnRow, lp(14))

        // Срабатывание
        val trig = panel(root, "Срабатывание")
        slider(trig, "Порог шума", "thr", -80, -5, -40) { "$it dB" }
        slider(trig, "Шум должен длиться", "attack10", 1, 50, 5) { "%.1f с".format(it / 10.0) }
        slider(trig, "Играть после тишины ещё", "hold", 2, 300, 20) { fmtSec(it) }
        slider(trig, "Пауза между срабатываниями", "cool", 0, 120, 5) { "$it с" }
        trig.addView(
            tv(
                "Пока звучит тон, микрофон не слышит частоты ниже 200 Гц, чтобы колонка не продлевала сама себя. " +
                    "Во время своих треков колонка раз в 15 с затихает на секунду и слушает, шумят ли ещё.",
                12f, MUTED
            ), lp(12)
        )

        // Расписание
        val sch = panel(root, "Расписание")
        sch.addView(check("Реагировать только в заданное время", "schedOn", false), lp(6))
        val times = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        lateinit var fromBtn: Button
        lateinit var toBtn: Button
        fromBtn = button(fmtTime(prefs.getInt("from", 540))) { pickTime("from", 540, fromBtn) }
        toBtn = button(fmtTime(prefs.getInt("to", 840))) { pickTime("to", 840, toBtn) }
        times.addView(tv("С", 15f, TEXT))
        times.addView(fromBtn, LinearLayout.LayoutParams(0, wrap, 1f).apply { leftMargin = dp(8); rightMargin = dp(12) })
        times.addView(tv("до", 15f, TEXT))
        times.addView(toBtn, LinearLayout.LayoutParams(0, wrap, 1f).apply { leftMargin = dp(8) })
        sch.addView(times, lp(8))

        val dayRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((name, bit) in DAYS) {
            val tb = ToggleButton(this).apply {
                textOn = name
                textOff = name
                textSize = 13f
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                setPadding(0, dp(10), 0, dp(10))
                stateListAnimator = null
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_checked), GradientDrawable().apply {
                        setColor(ACCENT); cornerRadius = dp(6).toFloat()
                    })
                    addState(intArrayOf(), GradientDrawable().apply {
                        setColor(PANEL); setStroke(dp(1), LINE); cornerRadius = dp(6).toFloat()
                    })
                }
                setTextColor(
                    ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                        intArrayOf(DARK, MUTED)
                    )
                )
                isChecked = (prefs.getInt("days", 0x7F) and (1 shl bit)) != 0
                setOnCheckedChangeListener { _, c ->
                    val d = prefs.getInt("days", 0x7F)
                    prefs.edit().putInt("days", if (c) d or (1 shl bit) else d and (1 shl bit).inv()).apply()
                }
            }
            dayRow.addView(tb, LinearLayout.LayoutParams(0, wrap, 1f).apply { leftMargin = dp(2); rightMargin = dp(2) })
        }
        sch.addView(dayRow, lp(10))
        schedInfo = tv("", 13f, MUTED)
        sch.addView(schedInfo, lp(10))

        // Фон
        val bgp = panel(root, "Работа в фоне")
        bgp.addView(
            tv(
                "Приложение слушает в фоне, экран можно выключать. В шторке висит уведомление — это нормально. " +
                    "На Samsung исключите приложение из экономии батареи, иначе система может его остановить.",
                13f, MUTED
            ), lp(6)
        )
        bgp.addView(button("Не ограничивать в фоне") { askBattery() }, lp(10))

        // Статистика
        val st = panel(root, "Статистика")
        statsText = tv("", 14f, TEXT).apply { typeface = Typeface.MONOSPACE }
        st.addView(statsText, lp(6))
    }

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            settingsBtn.visibility = View.GONE
            if (uiErr?.startsWith("Нет доступа") == true) uiErr = null
        }
        ui.post(tick)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tick)
    }

    // ---------- Действия ----------

    private fun toggle() {
        if (VibroState.running) {
            stopService(Intent(this, VibroService::class.java))
            return
        }
        val need = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            need += Manifest.permission.POST_NOTIFICATIONS
        }
        if (need.isEmpty()) startSvc() else requestPermissions(need.toTypedArray(), REQ_PERM)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERM) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            settingsBtn.visibility = View.GONE
            startSvc()
        } else {
            uiErr = "Нет доступа к микрофону. Нажмите кнопку ниже → Разрешения → Микрофон → Разрешить."
            settingsBtn.visibility = View.VISIBLE
        }
    }

    private fun startSvc() {
        VibroState.error = null
        uiErr = null
        val i = Intent(this, VibroService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
    }

    private fun test() {
        if (!VibroState.running) {
            uiErr = "Сначала нажмите «Начать слушать»."
            return
        }
        uiErr = null
        VibroState.testRequest = true
    }

    private fun calibrate(btn: Button) {
        if (!VibroState.running) {
            uiErr = "Сначала нажмите «Начать слушать»."
            return
        }
        if (VibroState.playing) {
            uiErr = "Дождитесь, пока колонка замолчит."
            return
        }
        uiErr = null
        btn.isEnabled = false
        val vals = ArrayList<Float>()
        var k = 0
        val r = object : Runnable {
            override fun run() {
                vals += VibroState.level
                k++
                if (k < 50) {
                    btn.text = "Тишина… ${5 - k / 10}"
                    ui.postDelayed(this, 100)
                } else {
                    vals.sort()
                    val bg = vals[(vals.size * 0.9).toInt().coerceAtMost(vals.size - 1)]
                    val t = (bg + 12f).roundToInt().coerceIn(-80, -5)
                    prefs.edit().putInt("thr", t).apply()
                    bars["thr"]?.progress = t + 80
                    btn.text = "Калибровка порога"
                    btn.isEnabled = true
                    Toast.makeText(this@MainActivity, "Порог: $t dB", Toast.LENGTH_SHORT).show()
                }
            }
        }
        btn.text = "Тишина… 5"
        ui.post(r)
    }

    private fun pickTime(key: String, def: Int, b: Button) {
        val v = prefs.getInt(key, def)
        TimePickerDialog(this, { _, h, m ->
            prefs.edit().putInt(key, h * 60 + m).apply()
            b.text = fmtTime(h * 60 + m)
        }, v / 60, v % 60, true).show()
    }

    private fun pickFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
        }
        try {
            startActivityForResult(i, REQ_FILE)
        } catch (e: Exception) {
            uiErr = "На телефоне не нашлось выбора файлов."
        }
    }

    private fun pickFolder() {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_DIR)
        } catch (e: Exception) {
            uiErr = "На телефоне не нашлось выбора папок."
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQ_FILE -> {
                keepAccess(uri, "fileUri")
                val name = Tracks.name(this, uri) ?: "Файл"
                prefs.edit().putString("fileUri", uri.toString()).putString("fileName", name)
                    .putString("trackSrc", "file").apply()
            }
            REQ_DIR -> {
                keepAccess(uri, "dirUri")
                val name = Tracks.treeName(this, uri) ?: "Папка"
                prefs.edit().putString("dirUri", uri.toString()).putString("dirName", name)
                    .putString("trackSrc", "dir").apply()
            }
            else -> return
        }
        uiErr = null
        updateTrackInfo()
    }

    /** Запоминает доступ к новому файлу/папке и отпускает старый — у Android лимит на такие разрешения. */
    private fun keepAccess(uri: Uri, key: String) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
        }
        val old = prefs.getString(key, null) ?: return
        if (old == uri.toString()) return
        try {
            contentResolver.releasePersistableUriPermission(Uri.parse(old), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
        }
    }

    private fun updateTrackInfo() {
        val dir = prefs.getString("dirUri", null)
        if (prefs.getString("trackSrc", "file") == "dir" && dir != null) {
            val name = prefs.getString("dirName", null) ?: "Папка"
            trackInfo.text = "Папка «$name»: ищу треки…"
            Thread {
                val text = try {
                    val n = Tracks.scan(applicationContext, Uri.parse(dir)).size
                    if (n == 0) "Папка «$name»: аудиофайлов не найдено."
                    else "Папка «$name»: треков — $n. При каждом срабатывании играют в случайном порядке."
                } catch (e: Exception) {
                    "Папка «$name»: нет доступа, выберите её заново."
                }
                ui.post {
                    // Пока сканировали, могли выбрать другое
                    if (!isDestroyed && prefs.getString("dirUri", null) == dir &&
                        prefs.getString("trackSrc", "file") == "dir"
                    ) trackInfo.text = text
                }
            }.start()
            return
        }
        val file = prefs.getString("fileName", null)
        trackInfo.text = if (prefs.getString("fileUri", null) != null) "Файл: ${file ?: "выбран"} (по кругу)"
        else "Ничего не выбрано — будет играть обычный тон."
    }

    private fun askBattery() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "Уже работает без ограничений", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                openAppSettings()
            }
        }
    }

    private fun openAppSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        } catch (e: Exception) {
        }
    }

    // ---------- Обновление экрана ----------

    private fun refresh() {
        val s = VibroState
        val cfg = Cfg.load(prefs)
        if (s.running) {
            goBtn.text = "Остановить"
            goBg.setColor(LIVE); goBg.setStroke(dp(1), LIVE)
            goBtn.setTextColor(Color.WHITE)
        } else {
            goBtn.text = "Начать слушать"
            goBg.setColor(ACCENT); goBg.setStroke(dp(1), ACCENT)
            goBtn.setTextColor(DARK)
        }
        val (label, color) = when {
            s.playing -> "ИГРАЕТ" to LIVE
            s.running && !s.active -> "ЖДЁТ ВРЕМЕНИ" to ACCENT
            s.running -> "СЛУШАЕТ" to OKC
            else -> "СТОП" to MUTED
        }
        status.text = label
        status.setTextColor(color)
        levelText.text = if (s.running && s.level > -99f) "%.0f dBFS".format(s.level) else "— dBFS"
        levelView.invalidate()
        statsText.text = "Срабатываний: ${s.fires}\nИграло всего: ${fmtDur(s.totalPlay())}\nПоследнее:    ${s.lastFire}"
        schedInfo.text = schedText(cfg)
        val err = s.error ?: uiErr
        errText.text = err ?: ""
        errText.visibility = if (err == null) View.GONE else View.VISIBLE
    }

    private fun schedText(c: Cfg): String {
        if (!c.schedOn) return "Расписание выключено: реагирует круглосуточно."
        if (c.days == 0) return "Не выбран ни один день: реагировать не будет."
        val span = if (c.from == c.to) "весь день"
        else fmtTime(c.from) + "–" + fmtTime(c.to) + (if (c.from > c.to) " (через полночь)" else "")
        val days = if (c.days == 0x7F) "каждый день"
        else "дни: " + DAYS.filter { (c.days and (1 shl it.second)) != 0 }.joinToString(", ") { it.first }
        val now = if (c.inWindow()) "Сейчас в расписании." else "Сейчас вне расписания."
        return "$now Работает $span, $days."
    }

    // ---------- Построение интерфейса ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(top) }

    private fun tv(s: String, size: Float = 14f, color: Int = TEXT, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun panel(root: LinearLayout, title: String): LinearLayout {
        val p = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = GradientDrawable().apply {
                setColor(PANEL)
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), LINE)
            }
        }
        p.addView(tv(title.uppercase(), 11f, MUTED, true).apply { letterSpacing = 0.1f })
        root.addView(p, lp(16))
        return p
    }

    private fun button(label: String, bg: Int = PANEL, fg: Int = TEXT, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            setTextColor(fg)
            stateListAnimator = null
            background = GradientDrawable().apply {
                setColor(bg)
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), if (bg == PANEL) LINE else bg)
            }
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { onClick() }
        }

    private fun check(label: String, key: String, def: Boolean): CheckBox = CheckBox(this).apply {
        text = label
        setTextColor(TEXT)
        buttonTintList = ColorStateList.valueOf(ACCENT)
        isChecked = prefs.getBoolean(key, def)
        setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean(key, c).apply() }
    }

    private fun slider(parent: LinearLayout, title: String, key: String, lo: Int, hi: Int, def: Int, fmt: (Int) -> String) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val value = tv(fmt(prefs.getInt(key, def)), 14f, ACCENT, true)
        row.addView(tv(title, 14f, TEXT), LinearLayout.LayoutParams(0, wrap, 1f))
        row.addView(value)
        parent.addView(row, lp(14))
        val sb = SeekBar(this)
        sb.max = hi - lo
        sb.progress = prefs.getInt(key, def) - lo
        sb.progressTintList = ColorStateList.valueOf(ACCENT)
        sb.thumbTintList = ColorStateList.valueOf(ACCENT)
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                val v = p + lo
                value.text = fmt(v)
                prefs.edit().putInt(key, v).apply()
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        parent.addView(sb, lp(2))
        bars[key] = sb
    }

    private fun fmtSec(s: Int): String =
        if (s < 60) "$s с" else "${s / 60} мин" + (if (s % 60 != 0) " ${s % 60} с" else "")

    private fun fmtDur(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    companion object {
        const val REQ_PERM = 1
        const val REQ_FILE = 2
        const val REQ_DIR = 3
    }
}

/** График уровня за последние ~9 секунд с линией порога. */
class LevelView(ctx: Context, private val thr: () -> Int) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w = width.toFloat()
        val h = height.toFloat()
        val t = thr().toFloat()
        fun y(d: Float) = h - ((d.coerceIn(-90f, 0f) + 90f) / 90f) * h

        c.drawColor(0xFF0B0F0D.toInt())
        p.strokeWidth = 1f
        p.color = 0xFF1F2925.toInt()
        var g = -80f
        while (g < 0f) {
            c.drawLine(0f, y(g), w, y(g), p)
            g += 20f
        }
        val hist = VibroState.hist
        val n = hist.size
        val pos = VibroState.histPos
        val bw = w / n
        for (i in 0 until n) {
            val d = hist[(pos + i) % n]
            p.color = if (d > t) LIVE else 0xFF3D5A4C.toInt()
            c.drawRect(i * bw, y(d), (i + 1) * bw + 0.5f, h, p)
        }
        p.color = ACCENT
        p.strokeWidth = 4f
        c.drawLine(0f, y(t), w, y(t), p)
    }
}
