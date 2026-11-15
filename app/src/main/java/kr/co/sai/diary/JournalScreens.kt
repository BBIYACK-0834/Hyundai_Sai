package kr.co.sai.diary

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.PictureDrawable
import android.os.Bundle
import android.text.InputFilter
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.Toast
import com.caverock.androidsvg.SVG
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

internal class JournalScreens(private val host: MainActivity, state: Bundle?) {
    private val db = JournalStore(host)
    private enum class SpeechPhase { STARTING, RECORDING, TRANSCRIBING }

    private data class VoiceDraft(
        val text: String,
        val moment: String,
        val recordedAt: LocalDateTime,
        val id: String = UUID.randomUUID().toString()
    )

    private var speech: DiarySpeech? = null
    private var speechPhase: SpeechPhase? = null
    private var voiceDraft: VoiceDraft? = null
    private var microphonePermissionPending = false
    private var recordingGoal = false
    private var pendingGoalText: String? = null
    private var animateBloom = false
    private var latestSavedId: String? = state?.getString("journal_latest_saved")
    private val pictures = mutableMapOf<String, Drawable.ConstantState>()
    private val files = host.assets.list("art").orEmpty()
        .associate { it.substringBeforeLast('.') to "art/$it" } + ("sprout" to "sprout.svg")
    private val characterSheet by lazy {
        host.assets.open("art/book2.png").use { BitmapFactory.decodeStream(it) }
    }
    private val brown = "#4b372f"
    private val muted = "#806d60"
    private val paper = "#fffefa"
    private val green = "#e9efdf"
    private val rose = "#9c625b"
    private var page = state?.getString("journal_page") ?: "home"
    private var month = state?.getString("journal_month")?.let(YearMonth::parse) ?: YearMonth.now()
    private var half = state?.getInt("journal_half") ?: (month.monthValue - 1) / 6
    private var dayPage = state?.getInt("journal_dates") ?: 0
    private var detailId = state?.getString("journal_detail")
    private var detailBack = state?.getString("journal_detail_back") ?: "dates"
    private var draftYear = state?.getInt("journal_year_draft") ?: month.year
    private var detailTimeId: String? = state?.getString("journal_detail_time")
    private var detailTab = state?.getInt("journal_detail_tab") ?: 0
    private var flower = Mood.entries.getOrElse(state?.getInt("journal_flower") ?: 0) { Mood.CALM }
    private var flowerBack = state?.getString("journal_flower_back") ?: "flowers"
    private var drive = 0
    private var goalChoice = 0
    private var goalDays = 7
    private var message = ""
    private var music: android.media.MediaPlayer? = null
    private val today get() = LocalDate.now()
    private val weather get() = host.store.weather()
    private val night get() = weather.startsWith("밤")
    private val sky get() = listOf("맑음", "흐림", "비", "눈").indexOf(weather.substringAfter(' ')).coerceAtLeast(0)

    fun saveState(out: Bundle) {
        out.putString("journal_page", page)
        out.putString("journal_latest_saved", latestSavedId)
        out.putString("journal_month", month.toString())
        out.putInt("journal_half", half)
        out.putInt("journal_dates", dayPage)
        out.putString("journal_detail", detailId)
        out.putString("journal_detail_back", detailBack)
        out.putInt("journal_detail_tab", detailTab)
        out.putInt("journal_year_draft", draftYear)
        out.putString("journal_detail_time", detailTimeId)
        out.putInt("journal_flower", flower.ordinal)
        out.putString("journal_flower_back", flowerBack)
    }

    fun go(next: String) {
        if (next != page) cancelRecording()
        if (next != page) stopMusic()
        if (next == "years") draftYear = month.year
        if (next == "books" && page == "home") {
            month = YearMonth.now()
            half = (month.monthValue - 1) / 6
        }
        page = next
        host.render()
    }

    fun back(): Boolean {
        if (page == "home" && speechPhase != null && speechPhase != SpeechPhase.TRANSCRIBING) {
            cancelSpeech()
            message = ""
            host.render()
            return true
        }
        val next = when (page) {
            "home" -> return false
            "dates", "years" -> "books"
            "detail" -> detailBack
            "goal_edit" -> "goals"
            "flower" -> flowerBack
            "bed", "flowers" -> "garden"
            "month_summary", "streak" -> "summary"
            else -> "home"
        }
        go(next)
        return true
    }

    init {
        if (!db.upgradeExistingDemo()) message = "예시 기록을 갱신하지 못했어요. 앱을 다시 열어주세요."
    }

    fun prepareDemoDay() = db.prepareDemoRecords(today)

    fun clear(): Boolean {
        cancelSpeech()
        stopMusic()
        voiceDraft = null
        pendingGoalText = null
        latestSavedId = null
        page = "home"
        message = ""
        month = YearMonth.now()
        half = (month.monthValue - 1) / 6
        dayPage = 0
        detailId = null
        return db.clear()
    }

    fun stopTransientWork() {
        cancelRecording()
        stopMusic()
    }

    private fun cancelRecording() {
        if (speechPhase != SpeechPhase.TRANSCRIBING) cancelSpeech()
    }

    fun cancelSpeech() {
        speech?.cancel()
        speech = null
        speechPhase = null
    }

    private fun stopMusic() {
        music?.release()
        music = null
    }

    private fun msg(text: String) = Toast.makeText(host, text, Toast.LENGTH_SHORT).show()

    fun render() {
        if (page == "home") art((if (night) "night" else "day") + sky, 0, 0, 1024, 552)
        header()
        when (page) {
            "home" -> home()
            "drive" -> drive()
            "books" -> books()
            "years" -> years()
            "dates" -> dates()
            "detail" -> detail()
            "goals", "goal_edit" -> goals()
            "summary", "month_summary", "recap", "streak" -> overview()
            "garden", "bed", "flowers", "flower" -> garden()
        }
        if (page != "drive") nav()
    }

    private fun art(name: String, x: Number, y: Number, w: Number, h: Number, description: String? = null) {
        if (name == "mind-violet") {
            val violet = object : View(host) {
                override fun onDraw(canvas: Canvas) {
                    // The third character in the first row of the original Figma artwork.
                    val source = Rect(588, 17, 820, 275)
                    val scale = minOf(width.toFloat() / source.width(), height.toFloat() / source.height())
                    val left = ((width - source.width() * scale) / 2).toInt()
                    val top = ((height - source.height() * scale) / 2).toInt()
                    val destination = Rect(left, top, left + (source.width() * scale).toInt(),
                        top + (source.height() * scale).toInt())
                    canvas.drawBitmap(characterSheet, source, destination, null)
                }
            }
            violet.contentDescription = description
            host.surface.place(violet, x, y, w, h)
            return
        }
        imageView(name, description)?.let { host.surface.place(it, x, y, w, h) }
    }

    private fun imageView(name: String, description: String?): ImageView? {
        val filename = files[name] ?: return null
        val drawable = pictures[name]?.newDrawable() ?: host.assets.open(filename).use {
            if (filename.endsWith(".svg")) PictureDrawable(SVG.getFromInputStream(it).renderToPicture())
            else Drawable.createFromStream(it, filename)
        } ?: return null
        drawable.constantState?.let { pictures[name] = it }
        return ImageView(host).apply {
            setImageDrawable(drawable)
            if (filename.endsWith(".svg")) setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = description
            if (description == null) importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    private fun panel(x: Number, y: Number, w: Number, h: Number, bg: String = paper) =
        host.panel(x, y, w, h, bg)

    private fun label(text: String, x: Number, y: Number, w: Number, h: Number,
                      size: Float = 16f, fg: String = brown, bold: Boolean = false, center: Boolean = false,
                      lines: Int = 2) = host.label(text, x, y, w, h, size, fg, bold).apply {
        maxLines = lines
        ellipsize = TextUtils.TruncateAt.END
        if (center) gravity = Gravity.CENTER
    }

    private fun button(text: String, x: Number, y: Number, w: Number, h: Number,
                       bg: String = green, fg: String = brown, selected: Boolean = false,
                       action: () -> Unit) = host.button(text, x, y, w, h, 16f, bg, fg, selected, action)

    private fun hit(description: String, x: Number, y: Number, w: Number, h: Number, action: () -> Unit) {
        button("", x, y, w, h, "#00000000", brown, action = action).contentDescription = description
    }

    private fun header() {
        val demoFuelPercent = 65
        val onDark = page == "home" && night
        val text = if (onDark) paper else brown
        label(if (page == "drive") "주행 화면 시연" else if (page == "home") today.format(dateFormat) else "",
            38, 15, 550, 40, 18f, text)
        panel(630, 16, 356, 39)
        panel(635, 20, 37, 31, if (page == "drive") "#f7edce" else green)
        label(if (page == "drive") "D" else "P", 635, 20, 37, 31, 17f, bold = true, center = true)
        art("fuel", 690, 26, 17, 18)
        label("$demoFuelPercent%", 715, 17, 44, 37).contentDescription =
            "연료 잔량 $demoFuelPercent 퍼센트, 시연용 더미 데이터"
        hit("연료 잔량 안내", 684, 17, 76, 37) {
            msg("연료 잔량 ${demoFuelPercent}% · 시연용 데이터예요.")
        }
        val icon = if (night) listOf("moon", "night_cloud", "night_rain", "night_snow")[sky]
            else listOf("sun", "cloud", "rain", "snow")[sky]
        art(icon, 781, 26, 18, 18)
        label(weather.substringAfter(' '), 806, 17, 56, 37)
        art("clock", 872, 26, 18, 18)
        label(if (night) "18:40" else "08:30", 898, 17, 77, 37)
        panel(38, 62, 948, 1, "#e3d7cb")
        if (page == "home") {
            button(if (night) "낮으로 전환" else "밤으로 전환", 431, 16, 158, 39, paper) {
                if (speechPhase != null || voiceDraft != null || pendingGoalText != null) {
                    msg("진행 중인 녹음과 저장을 마친 뒤 전환해 주세요.")
                    return@button
                }
                val target = if (night) "낮" else "밤"
                if (target == "밤" && !db.bloomToday(today, latestSavedId)) {
                    msg("꽃을 저장하지 못했어요.")
                    return@button
                }
                if (!host.store.setWeather("$target ${weather.substringAfter(' ')}")) {
                    msg("시간을 바꾸지 못했어요.")
                    return@button
                }
                animateBloom = target == "밤"
                latestSavedId = null
                message = ""
                host.render()
            }
            hit("시연 날씨 선택", 768, 16, 96, 39) { cancelRecording(); host.changeWeather() }
            hit("주행 화면 시연", 630, 16, 46, 39) { drive = 0; go("drive") }
        }
    }

    private fun nav() {
        val dark = page == "home" && night
        val active = when (page) {
            "home" -> 1
            "books", "years", "dates", "detail" -> 0
            else -> 2
        }
        panel(38, 463, 948, 1, if (dark) "#7b8290" else "#e6dbce")
        listOf("기록", "홈", "마이페이지").forEachIndexed { i, text ->
            val base = 38 + i * 319
            val icon = listOf("tab_records", if (dark) "tab_home_night" else "tab_home",
                if (dark) "tab_me_night" else "tab_me")[i]
            art(icon, base + if (i == 2) 101 else 123, 488, 27, 27)
            label(text, base + if (i == 2) 134 else 155, 476, 125, 52, 16f,
                if (dark) paper else if (i == active) rose else brown, i == active)
            if (i == active) panel(base + 140, 522, 28, 3, if (dark) "#f6d9ca" else rose)
            hit(text, base, 472, 309, 60) { go(listOf("books", "home", "summary")[i]) }
        }
    }

    private fun notebook(x: Int) {
        panel(x + 5, 85, 280, 372, "#22000000")
        panel(x, 80, 280, 372)
        panel(x + 11, 113, 1, 324, "#e9cfc7")
        repeat(8) { i ->
            panel(x + 27 + 32 * i, 90, 9, 8, "#e8ddc7")
            panel(x + 29 + 32 * i, 73, 5, 22, "#b8a98d")
        }
    }

    private fun tasks() = db.tasks(host.profile.goal)
    private fun completion(): Int {
        val goals = tasks()
        return if (goals.isEmpty()) 0 else goals.count { it in db.checked() } * 100 / goals.size
    }

    private fun goalList(x: Int, y: Int, designWidth: Int, height: Int, rowHeight: Int, color: String) {
        val rows = android.widget.LinearLayout(host).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        for (task in tasks()) {
            val checked = task in db.checked()
            val item = Button(host).apply {
                text = (if (checked) "✓  " else "□  ") + task
                contentDescription = task
                isSelected = checked
                isAllCaps = false
                minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
                setPadding(8, 0, 8, 0)
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(Color.parseColor(brown))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 10f
                    setColor(Color.parseColor(color))
                }
                setOnClickListener {
                    if (db.toggle(task)) host.render() else msg("목표를 저장하지 못했어요.")
                }
            }
            rows.addView(item, android.widget.LinearLayout.LayoutParams(-1, rowHeight))
        }
        val scroll = object : ScrollView(host) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val scale = MeasureSpec.getSize(widthMeasureSpec) / designWidth.toFloat()
                for (index in 0 until rows.childCount) {
                    val item = rows.getChildAt(index) as Button
                    (item.layoutParams as android.widget.LinearLayout.LayoutParams).apply {
                        this.height = (rowHeight * scale).toInt()
                        bottomMargin = (3 * scale).toInt()
                    }
                    item.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 15f * scale)
                }
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }.apply { addView(rows) }
        host.surface.place(scroll, x, y, designWidth, height)
    }

    private fun awaitingMorningGoal() = !night && !db.hasMorningGoal(today)

    private fun home() {
        val records = db.entries()
        val date = if (night) today else today.minusDays(1)
        val entries = records.filter { it.date == date }
        val recent = entries.firstOrNull()
        val todayFlower = if (night) entries.firstNotNullOfOrNull { it.flower } else null
        notebook(38)
        notebook(706)
        label(if (night) "오늘의 일기" else "어제의 일기", 60, 104, 235, 28, 14f, "#536548", true)
        label(date.format(shortDate), 59, 138, 240, 30, 20f, bold = true)
        panel(59, 176, 239, 45, "#f3ebdd")
        label(if (entries.isEmpty()) "아직 남긴 일기가 없어요." else summarizeDay(entries).replace("\n", " "), 68, 176, 219, 45, 15f)
        if (recent != null) {
            button(if (night) "오늘 일기 보기 ›" else "전날 일기 보기 ›", 59, 224, 239, 35, paper) {
                openDiary(recent, "home")
            }
        }
        panel(59, 264, 239, 1, "#e6dbce")
        label(if (night) "오늘의 감정" else "어제의 감정", 59, 268, 239, 32, 15f, bold = true)
        bars(entries, 59, 302, 238, 23, 6)

        panel(348, 96, 328, 83)
        art("tail", 499.2, 178.8, 25.6, 15.2)
        label(message.ifBlank { if (night) "오늘은 어떤 하루였나요?" else if (awaitingMorningGoal()) "오늘 이루고 싶은\n목표는 무엇인가요?" else "목표를 정했어요.\n지금의 마음을 들려주세요." },
            365, 100, 294, 74, 21f, bold = true, center = true)
        art(if (todayFlower != null) characterArt(todayFlower) else "sprout",
            409.6, 181.6, 204.8, 177, if (todayFlower == null) "씨앗" else todayFlower.flower)
        if (animateBloom && todayFlower != null) {
            val character = host.surface.getChildAt(host.surface.childCount - 1)
            character.alpha = 0f
            character.scaleX = 0.65f
            character.scaleY = 0.65f
            character.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(850).start()
        }
        animateBloom = false
        val latestSaved = records.firstOrNull { it.id == latestSavedId }.takeUnless { awaitingMorningGoal() }
        when (speechPhase) {
            SpeechPhase.RECORDING -> {
                panel(348, 356, 328, 72, "#fff6ec")
                art("recording_dot", 397, 369, 9.2, 9.2)
                label(if (recordingGoal) "목표 녹음 중" else "녹음 중", 412, 360, 100, 24, 15f, bold = true)
                art("recording_wave", 528, 361, 98, 22)
                button("취소", 361, 390, 95, 33, "#f2e8e0") {
                    cancelSpeech()
                    message = ""
                    host.render()
                }
                button(if (recordingGoal) "목표 정하기" else "일시정지", 463, 390, 200, 33, rose, paper) { handleVoiceControl() }
            }
            SpeechPhase.STARTING, SpeechPhase.TRANSCRIBING -> {
                panel(348, 371, 328, 45, "#fff6ec")
                label(if (speechPhase == SpeechPhase.STARTING) "마이크 준비 중…" else "음성 인식 중…",
                    348, 371, 328, 45, 15f, center = true)
            }
            null -> {
                if (voiceDraft == null && latestSaved != null) {
                    panel(348, 371, 328, 45, "#fffdf8")
                    art("recording_saved", 366, 383, 19.2, 19.2)
                    label("일기를 저장했어요", 394, 371, 181, 45, 15f, bold = true)
                    button("확인", 583, 376, 83, 35) {
                        latestSavedId = null
                        message = ""
                        host.render()
                    }
                    button("방금 저장한 일기 보기 ›", 372, 430, 280, 31, paper) { openDiary(latestSaved, "home") }
                } else {
                    panel(481, 374, 62, 59, "#eaccc4")
                    panel(487, 379, 50, 50, "#a46057")
                    art("mic", 506, 392, 12, 18)
                    val caption = when {
                        pendingGoalText != null || voiceDraft != null -> "다시 저장"
                        awaitingMorningGoal() -> "목표 답하기"
                        else -> "일기 녹음"
                    }
                    panel(452, 431, 120, 24, "#fff5ea")
                    label(caption, 452, 431, 120, 24, 14f, center = true)
                    hit(caption, 451, 373, 122, 82) { handleVoiceControl() }
                }
            }
        }

        if (awaitingMorningGoal() && speechPhase == null && pendingGoalText == null) {
            button("글로 목표 답하기", 372, 451, 280, 20, paper) { editGoalAnswer() }
        }

        if (night) {
            label("오늘의 꽃", 731, 109, 225, 33, 20f, bold = true)
            if (todayFlower == null) {
                art("morning", 773, 172, 150, 150)
                label("오늘의 일기를 남기고\n마음꽃을 피워보세요.", 731, 312, 230, 66, 17f, center = true)
                if (recent != null) button("꽃 피우기", 731, 390, 229, 42, rose, paper) { chooseFlower(recent) }
            } else {
                label(todayFlower.flower, 731, 157, 230, 49, 25f, bold = true)
                panel(731, 220, 229, 45, "#f0e0dd")
                label("꽃말   ${todayFlower.meaning}", 743, 220, 205, 45, 17f)
                label(todayFlower.message, 731, 278, 229, 70, 16f, lines = 3)
                button("꽃 자세히 보기", 731, 388, 229, 42) {
                    openFlower(todayFlower, "home")
                }
            }
        } else {
            label("오늘의 목표", 727, 105, 225, 29, 14f, "#536548", true)
            if (tasks().size > 2) label("위아래로 보기 ↕", 838, 109, 121, 20, 11f, muted)
            goalList(727, 139, 238, 99, 44, paper)
            button("목표 살펴보기", 727, 245, 238, 37, rose, paper) { go("goals") }
            button("음성으로 목표 설정", 727, 293, 238, 37) {
                if (speechPhase == null && voiceDraft == null) {
                    message = "오늘 이루고 싶은 일을 말해 주세요."
                    handleVoiceControl(forGoal = true)
                }
            }
            panel(727, 338, 238, 1, "#e6dbce")
            label("마음에 어울리는 음악", 727, 344, 238, 26, 15f, bold = true)
            label("햇살을 닮은 멜로디", 727, 371, 238, 33)
            button(if (music == null) "▶ 음악 듣기" else "■ 음악 멈추기",
                727, 413, 238, 32, "#eadab4") { playMusic() }
        }
    }

    private fun playMusic() {
        if (music != null) {
            stopMusic()
            host.render()
            return
        }
        music = android.media.MediaPlayer.create(host, R.raw.sunny_melody)?.apply {
            setOnCompletionListener { stopMusic(); host.render() }
            start()
        }
        host.render()
    }

    private fun handleVoiceControl(forGoal: Boolean = false) {
        if (speechPhase == SpeechPhase.RECORDING) {
            speechPhase = SpeechPhase.TRANSCRIBING
            message = "음성을 글로 바꾸고 있어요."
            speech?.finish()
            host.render()
            return
        }
        pendingGoalText?.let { saveGoalTranscript(it); return }
        voiceDraft?.let { saveVoiceDiary(it); return }
        if (microphonePermissionPending || speechPhase != null) return
        recordingGoal = forGoal || awaitingMorningGoal()
        if (host.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            microphonePermissionPending = true
            host.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 42)
        } else {
            startSpeech()
        }
    }

    fun permissionResult(granted: Boolean) {
        microphonePermissionPending = false
        if (!host.isJournalVisible || page != "home") return
        if (granted) startSpeech()
        else if (recordingGoal) editGoalAnswer()
        else editDiary(null, "마이크 권한 없이 글로 기록할 수 있어요.")
    }

    private fun startSpeech() {
        val recordedAt = LocalDateTime.now()
        val forGoal = recordingGoal
        val moment = if (night) "도착" else "출발"
        lateinit var session: DiarySpeech
        session = DiarySpeech(host, object : DiarySpeech.Listener {
            override fun onRecording() = host.runOnUiThread {
                if (speech !== session || speechPhase != SpeechPhase.STARTING) return@runOnUiThread
                speechPhase = SpeechPhase.RECORDING
                message = "음성 녹음 중"
                host.render()
            }

            override fun onTranscribing() = host.runOnUiThread {
                if (speech !== session) return@runOnUiThread
                speechPhase = SpeechPhase.TRANSCRIBING
                message = "음성을 글로 바꾸고 있어요."
                host.render()
            }

            override fun onResult(text: String?, error: String?) = host.runOnUiThread {
                if (speech !== session) return@runOnUiThread
                speech = null
                speechPhase = null
                if (text.isNullOrBlank()) {
                    message = error ?: "다시 말해 주세요."
                    if (!host.isJournalVisible || page != "home") msg(message)
                    host.render()
                } else if (forGoal) {
                    message = ""
                    host.render()
                    editGoalAnswer(text, recordedAt.toLocalDate())
                } else {
                    saveVoiceDiary(VoiceDraft(text, moment, recordedAt))
                }
            }
        })
        speech = session
        speechPhase = SpeechPhase.STARTING
        message = "마이크 준비 중…"
        stopMusic()
        session.start()
        host.render()
    }

    private fun editGoalAnswer(transcript: String? = null, date: LocalDate = today) {
        val input = EditText(host).apply {
            hint = "예: 오늘은 퇴근 후 20분 산책하고 싶어요"
            contentDescription = "첫 질문의 목표 답변"
            maxLines = 4
            filters = arrayOf(InputFilter.LengthFilter(1200))
            setText(transcript.orEmpty())
        }
        val builder = AlertDialog.Builder(host)
            .setTitle(if (transcript == null) "오늘 이루고 싶은 목표는 무엇인가요?" else "인식한 목표 확인")
            .setView(input).setNegativeButton("취소", null).setPositiveButton("목표로 저장", null)
        if (transcript != null) builder.setNeutralButton("다시 말하기") { _, _ -> handleVoiceControl(forGoal = true) }
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val answer = input.text.toString().trim()
                if (answer.isBlank()) { input.error = "목표를 입력해 주세요."; return@setOnClickListener }
                dialog.dismiss()
                saveGoalTranscript(answer, date)
            }
        }
        dialog.show()
    }

    private fun saveGoalTranscript(text: String, date: LocalDate = today) {
        val saved = db.saveSpokenGoal(text, date)
        pendingGoalText = if (saved) null else text
        latestSavedId = null
        message = if (saved) "오늘의 목표를 정했어요.\n이제 하루의 이야기를 들려주세요." else "목표를 저장하지 못했어요. 다시 저장해 주세요."
        if (!host.isJournalVisible || page != "home") msg(message)
        host.render()
    }

    private fun saveVoiceDiary(draft: VoiceDraft) {
        val entry = db.add(draft.text, draft.moment, voice = true, recordedAt = draft.recordedAt, id = draft.id, tags = emptyList())
        voiceDraft = if (entry == null) draft else null
        latestSavedId = entry?.id ?: latestSavedId
        message = if (entry == null) "홈에서 다시 저장해 주세요." else "오늘의 감정은 ${entry.emotion.title}이에요."
        if (!host.isJournalVisible || page != "home") msg(message)
        host.render()
    }

    private fun saveTypedDiary(body: String) {
        val entry = db.add(body, if (night) "도착" else "출발", voice = false, tags = emptyList())
        if (entry == null) {
            editDiary(null, "저장하지 못했어요. 다시 시도해 주세요.", body)
            return
        }
        latestSavedId = entry.id
        message = "오늘의 감정은 ${entry.emotion.title}이에요."
        host.render()
    }

    private fun editDiary(entry: Diary?, reason: String? = null, draft: String = entry?.body.orEmpty()) {
        val input = EditText(host).apply {
            setText(draft)
            contentDescription = "일기 본문"
            hint = "오늘의 마음을 적어주세요"
            minLines = 3
            maxLines = 5
            gravity = Gravity.TOP
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            filters = arrayOf(InputFilter.LengthFilter(12000))
        }
        val fields = android.widget.LinearLayout(host).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(24, 0, 24, 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(host).setTitle(if (entry == null) "일기 남기기" else "일기 수정")
            .setMessage(reason).setView(ScrollView(host).apply { addView(fields) }).setNegativeButton("취소", null)
            .setPositiveButton("저장", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = input.text.toString().trim()
                if (text.isBlank()) { input.error = "내용을 입력해 주세요."; return@setOnClickListener }
                if (entry == null) {
                    dialog.dismiss()
                    saveTypedDiary(text)
                } else if (db.replace(entry.copy(body = text, emotion = Mood.classify(text)))) {
                    dialog.dismiss()
                    host.render()
                } else msg("수정 내용을 저장하지 못했어요.")
            }
        }
        dialog.show()
    }

    private fun chooseFlower(entry: Diary) {
        val suggested = entry.mood
        val moods = if (suggested == null) Mood.entries.toList() else listOf(suggested) + Mood.entries.filter { it != suggested }
        AlertDialog.Builder(host).setTitle("오늘의 마음꽃")
            .setItems(moods.map { "${it.title} · ${it.flower}" + if (it == suggested) "  (추천)" else "" }.toTypedArray()) { _, i ->
                if (db.replace(entry.copy(flower = moods[i]))) {
                    message = moods[i].message
                    host.render()
                } else msg("꽃을 저장하지 못했어요.")
            }.setNegativeButton("나중에", null).show()
    }

    private fun openDiary(entry: Diary, returnTo: String) {
        detailId = entry.id
        detailBack = returnTo
        detailTab = 0
        detailTimeId = entry.id
        go("detail")
    }

    private fun openFlower(mood: Mood, returnTo: String) {
        flower = mood
        flowerBack = returnTo
        go("flower")
    }

    companion object {
        private val dateFormat = DateTimeFormatter.ofPattern("yyyy년 M월 d일 EEEE", Locale.KOREAN)
        private val clockFormat = DateTimeFormatter.ofPattern("HH:mm")
        private val shortDate = DateTimeFormatter.ofPattern("M월 d일 EEEE", Locale.KOREAN)
    }

    private fun characterArt(mood: Mood): String = when (mood) {
        Mood.CALM -> "evening"
        Mood.WORRY -> "mind-violet"
        Mood.TIRED -> "book5"
        Mood.JOY -> "book3"
        Mood.EXCITED -> "book4"
        Mood.SAD -> "book6"
    }

    private fun drive() {
        listOf("기본", "음성 반응", "바람 반응").forEachIndexed { i, value ->
            button(value, 250 + 140 * i, 82, 129, 36, if (drive == i) "#536548" else green,
                if (drive == i) paper else brown) { drive = i; host.render() }
        }
        button("정차 화면으로", 736, 82, 243, 36, "#f2e5df") { go("home") }
        panel(45, 152, 624, 296, green)
        art("road", 69, 168, 576, 256)
        label("300m 앞에서 우회전", 77, 167, 520, 50, 24f, bold = true)
        panel(696, 152, 283, 296, "#edf0e2")
        panel(721, 182, 233, 59)
        art("drive_tail", 828.5, 240.4, 18.2, 10.8)
        label(listOf("함께 가는 중이에요.", "이야기를 듣고 있어요.", "바람이 잎을 흔들어요.")[drive],
            721, 182, 233, 59, 16f, center = true)
        art("morning", 765, 242, 145, 145, "마음꽃")
        val character = host.surface.getChildAt(host.surface.childCount - 1)
        if (drive == 1) art("recording_wave", 807, 394, 61, 21, "음성 파형")
        when (drive) {
            1 -> character.animate().scaleX(1.12f).scaleY(1.12f).setDuration(350).withEndAction {
                character.animate().scaleX(1f).scaleY(1f).setDuration(350).start()
            }.start()
            2 -> character.animate().rotation(9f).setDuration(350).withEndAction {
                character.animate().rotation(-7f).setDuration(350).withEndAction {
                    character.animate().rotation(0f).setDuration(300).start()
                }.start()
            }.start()
        }
    }

    private fun books() {
        val records = db.entries()
        val entriesByMonth = records.filter { it.date.year == month.year }.groupBy { it.date.monthValue }
        if (entriesByMonth.isEmpty() && month.year != today.year) {
            art("sprout", 451, 120, 122, 122, "기록을 기다리는 새싹")
            label(month.year.toString(), 350, 255, 324, 49, 36f, bold = true, center = true)
            label("기록 없음", 350, 306, 324, 35, 17f, muted, center = true)
            button("연도 선택", 407, 361, 210, 43) { go("years") }
            return
        }
        val originalDemoDate = records.firstOrNull { it.id == "demo-yesterday" }?.date
        val demoDate = originalDemoDate ?: records.firstOrNull { it.isDemo }?.date
        val shortcuts = listOf("오늘 일기" to today, "어제 일기" to today.minusDays(1)) +
            demoDate?.let { listOf("시연 일기" to it) }.orEmpty()
        shortcuts.forEachIndexed { index, (title, date) ->
            val dayEntries = records.filter { it.date == date }
            if (dayEntries.isNotEmpty()) button(title, 46 + index * 121, 88, 110, 38) {
                selectDay(dayEntries, "books")
            }
        }
        button("${month.year} ﹀", 424, 81, 181, 44, paper) { go("years") }
        button("날짜별", 772, 88, 185, 38) {
            val value = if (month.year == today.year) YearMonth.now() else month
            openMonth(value, if (value == YearMonth.now()) (today.dayOfMonth - 1) / 6 else 0)
        }
        val covers = listOf("#e9cfc7", "#dbe4cf", "#efe0b6", "#e3d9ed", "#ead8bc", "#d4e2e1")
        val spines = listOf("#bd8478", "#889a70", "#c8a04e", "#a492bc", "#bc956b", "#819fa0")
        repeat(6) { i ->
            val m = half * 6 + i + 1
            val x = 133 + i % 3 * 313
            val y = 129 + i / 3 * 162
            panel(x + 7, y + 4, 124, 131, spines[i])
            panel(x + 8, y + 5, 122, 128, paper)
            panel(x, y, 124, 126, covers[i])
            panel(x, y, 19, 126, spines[i])
            repeat(4) { ring -> panel(x - 6, y + 19 + ring * 25, 16, 5, paper) }
            val entries = entriesByMonth[m].orEmpty()
            val coverFlower = entries.firstOrNull()?.mood
            if (coverFlower != null) art(characterArt(coverFlower), x + 27, y + 8, 94, 84)
            label(if (entries.isEmpty()) "기록 없음" else "${entries.map { it.date }.distinct().size}편",
                x + 20, y + 91, 104, 28, 13f, center = true)
            label("${m}월", x, y + 136, 124, 28, 17f, center = true)
            hit("${m}월 일기장", x - 6, y, 144, 156) { openMonth(YearMonth.of(month.year, m)) }
        }
        if (half == 1) button("‹", 38, 264, 48, 48) { half = 0; host.render() }
        if (half == 0) button("›", 938, 264, 48, 48) { half = 1; host.render() }
    }

    private fun years() {
        val lowest = minOf(today.year - 4, db.entries().minOfOrNull { it.date.year } ?: today.year)
        val highest = maxOf(today.year + 1, month.year)
        for (year in maxOf(lowest, draftYear - 2)..minOf(highest, draftYear + 2)) {
            val selected = year == draftYear
            val top = 214 + (draftYear - year) * 43
            if (selected) {
                panel(408, top, 208, 43, "#efe2d6")
                label(year.toString(), 408, top, 208, 43, 32f, bold = true, center = true)
            } else {
                button(year.toString(), 408, top, 208, 43, "#00000000", muted) {
                    draftYear = year
                    host.render()
                }
            }
        }
        button("뒤로", 341, 367, 158, 44, paper) { go("books") }
        button("확인", 520, 367, 158, 44, rose, paper) {
            month = month.withYear(draftYear)
            go("books")
        }
    }

    private fun openMonth(value: YearMonth, index: Int = 0) {
        month = value
        half = (value.monthValue - 1) / 6
        dayPage = index
        go("dates")
    }

    private fun dates() {
        button("‹ 일기장으로", 45, 82, 169, 42, paper) { go("books") }
        label("${month.monthValue}월", 271, 82, 420, 49, 26f, bold = true)
        val days = db.month(month).groupBy { it.date.dayOfMonth }
        val first = dayPage * 6 + 1
        val last = minOf(first + 5, month.lengthOfMonth())
        for (day in first..last) {
            val i = day - first
            val x = 62 + i % 3 * 306
            val y = 152 + i / 3 * 112
            val entries = days[day].orEmpty()
            val entry = entries.firstOrNull()
            panel(x, y, 289, 99, if (entry == null) "#f1ebe2" else green)
            label("${day}일", x + 14, y + 10, 190, 34, 22f, bold = true)
            label(entry?.summary ?: "기록 없음", x + 14, y + 48, 210, 26, 15f, lines = 1)
            label(entries.map { it.emotion.title }.distinct().joinToString(" · "), x + 14, y + 72, 210, 20, 13f, muted)
            if (entry == null) {
                val sprout = ImageView(host).apply {
                    setImageDrawable(host.assets.open("sprout.svg").use { PictureDrawable(SVG.getFromInputStream(it).renderToPicture()) })
                    setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = "아직 피지 않은 새싹"
                }
                host.surface.place(sprout, x + 222, y + 19, 50, 58)
            } else {
                art(characterArt(entry.mood), x + 219, y + 12, 63, 66)
                hit("${month.monthValue}월 ${day}일 일기 보기", x, y, 289, 99) { selectDay(entries, "dates") }
            }
        }
        for (slot in (last - first + 1) until 6) {
            val x = 62 + slot % 3 * 306
            val y = 152 + slot / 3 * 112
            panel(x, y, 289, 99, "#f1ebe2")
            label("—", x, y, 289, 99, 17f, muted, center = true)
        }
        if (dayPage > 0) button("‹ 이전 날짜", 139, 391, 164, 36) { dayPage--; host.render() }
        label("$first–${last}일  ·  ${dayPage + 1}/${(month.lengthOfMonth() + 5) / 6}", 360, 389, 304, 42, 17f, muted, center = true)
        if (last < month.lengthOfMonth()) button("다음 날짜 ›", 720, 391, 164, 36) { dayPage++; host.render() }
    }

    private fun selectDay(entries: List<Diary>, returnTo: String) {
        if (entries.all { it.date == entries.first().date }) openDiary(entries.first(), returnTo)
        else AlertDialog.Builder(host).setTitle("일기 선택")
            .setItems(entries.map { "${it.time.format(DateTimeFormatter.ofPattern("HH:mm"))}  ${it.moment} · ${it.summary}" }.toTypedArray()) { _, i ->
                openDiary(entries[i], returnTo)
            }.show()
    }

    private fun detail() {
        val records = db.entries()
        val entry = records.firstOrNull { it.id == detailId }
        if (entry == null) {
            button("‹ 뒤로", 45, 81, 169, 42, paper) { go(detailBack) }
            label("일기를 찾을 수 없어요.", 220, 190, 584, 90, 23f, center = true)
            return
        }
        button("‹ 월별 일기장", 45, 81, 169, 42, paper) {
            month = YearMonth.from(entry.date)
            half = (month.monthValue - 1) / 6
            go("books")
        }
        button("날짜별 목차", 805, 81, 169, 42) {
            openMonth(YearMonth.from(entry.date), (entry.date.dayOfMonth - 1) / 6)
        }
        val dayEntries = records.filter { it.date == entry.date }.sortedBy { it.time }
        val choices = if (detailTab == 2) dayEntries.filter { it.voice } else dayEntries
        val selected = choices.firstOrNull { it.id == detailTimeId } ?: choices.lastOrNull()
        val hasTimePicker = detailTab != 0 && choices.isNotEmpty()

        panel(47, 137, 257, 279, "#f0e9d9")
        panel(316, 137, 664, 279)
        label(entry.date.format(shortDate), 62, 154, 227, 40, 22f, bold = true, center = true)
        val dailyMood = representativeEmotion(dayEntries)
        val classifiedMood = if (detailTab == 0) dailyMood else selected?.emotion

        // One flower represents the whole day, independently of the selected time or tab.
        art(characterArt(dailyMood), 60, 211, 122, 132, "하루 대표 꽃: ${dailyMood.flower}")
        label("하루의 마음꽃", 60, 345, 122, 20, 12f, muted, center = true)
        label(dailyMood.flower, 60, 365, 122, 23, 15f, bold = true, center = true)
        panel(185, 213, 1, 170, "#dcd2bd")

        label("감정", 194, 211, 99, 23, 13f, bold = true)
        label(if (detailTab == 0) "하루 전체" else selected?.time?.format(clockFormat) ?: "기록 없음",
            194, 237, 99, 20, 12f, muted)
        label(classifiedMood?.title ?: "—", 194, 260, 99, 31, 21f, bold = true)
        label(if (detailTab == 0) "${dailyMood.title} · ${dayEntries.size}개의 기록"
            else "하루 대표 감정 · ${dailyMood.title}", 61, 394, 228, 19, 12f, muted, center = true)

        listOf("요약 일기", "시간별 기록", "음성 기록").forEachIndexed { i, value ->
            button(value, 344 + i * 148, 81, 136, 42, if (detailTab == i) green else paper,
                selected = detailTab == i) {
                detailTab = i
                host.render()
            }
        }
        if (hasTimePicker) timePicker(choices, selected)

        val content = when (detailTab) {
            0 -> summarizeDay(dayEntries)
            1 -> selected?.body.orEmpty()
            else -> selected?.let {
                (if (it.isDemo) "시연용 음성 변환 예시 · 실제 녹음 없음\n\n" else "") + it.body
            } ?: "음성으로 작성한 기록이 없어요."
        }
        val body = android.widget.TextView(host).apply {
            text = content
            textSize = 17f
            setTextColor(Color.parseColor(brown))
            setLineSpacing(5f, 1f)
        }
        val tagTop = if (hasTimePicker) 241 else 153
        val tags = if (detailTab == 0) dayEntries.map { it.emotion.title }.distinct()
            else selected?.let { listOf(it.emotion.title) }.orEmpty()
        if (tags.isNotEmpty()) {
            val tagText = android.widget.TextView(host).apply {
                text = "감정  " + tags.joinToString(" · ")
                contentDescription = "감정 태그: ${tags.joinToString(", ")}"
                setTextColor(Color.parseColor("#536548"))
                textSize = 14f
                setSingleLine(true)
            }
            host.surface.place(android.widget.HorizontalScrollView(host).apply { addView(tagText) },
                339, tagTop, 617, 28)
        }
        val bodyTop = if (tags.isEmpty()) tagTop else tagTop + 34
        val editing = selected?.takeIf { detailTab != 0 }
        val bodyBottom = if (editing == null) 398 else 360
        host.surface.place(ScrollView(host).apply { addView(body) }, 339, bodyTop, 617, bodyBottom - bodyTop)
        if (editing != null) {
            button("수정", 339, 367, 100, 39) { editDiary(editing) }
            if (editing.moment == "도착") button("꽃 선택", 449, 367, 123, 39) { chooseFlower(editing) }
            button("삭제", 854, 367, 103, 39, "#f1dfd9") {
                AlertDialog.Builder(host).setTitle("이 시간대의 일기를 삭제할까요?")
                    .setNegativeButton("취소", null).setPositiveButton("삭제") { _, _ ->
                        if (db.delete(editing.id)) {
                            val remaining = dayEntries.lastOrNull { it.id != editing.id }
                            if (remaining == null) go(detailBack) else openDiary(remaining, detailBack)
                        } else msg("일기를 삭제하지 못했어요.")
                    }.show()
            }
        }
        records.firstOrNull { it.date < entry.date }?.let { older ->
            button("‹ 이전 일기", 45, 421, 150, 34) { openDiary(older, detailBack) }
        }
        records.lastOrNull { it.date > entry.date }?.let { newer ->
            button("다음 일기 ›", 830, 421, 150, 34) { openDiary(newer, detailBack) }
        }
    }

    private fun timePicker(choices: List<Diary>, selected: Diary?) {
        label("시간대 선택", 339, 149, 220, 27, 15f, bold = true)
        if (choices.size > 4) label("좌우로 밀어 더 보기", 762, 149, 196, 27, 13f, muted)
        val row = android.widget.LinearLayout(host)
        var selectedButton: Button? = null
        for (item in choices) {
            val active = item.id == selected?.id
            val chip = Button(host).apply {
                val timeText = item.time.format(clockFormat)
                text = "$timeText · ${item.emotion.title}"
                contentDescription = "$timeText · ${item.emotion.title} 기록"
                isSelected = active
                isAllCaps = false
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                setPadding(0, 0, 0, 0)
                textSize = 15f
                setTextColor(Color.parseColor(if (active) paper else brown))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 10 * host.resources.displayMetrics.density
                    setColor(Color.parseColor(if (active) "#536548" else green))
                }
                setOnClickListener {
                    detailTimeId = item.id
                    host.render()
                }
            }
            row.addView(chip, android.widget.LinearLayout.LayoutParams(136, -1))
            if (active) selectedButton = chip
        }
        val scroll = object : android.widget.HorizontalScrollView(host) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val scale = MeasureSpec.getSize(widthMeasureSpec) / 617f
                for (i in 0 until row.childCount) {
                    val chip = row.getChildAt(i) as Button
                    val layout = chip.layoutParams as android.widget.LinearLayout.LayoutParams
                    layout.width = (142 * scale).toInt()
                    layout.marginEnd = (9 * scale).toInt()
                    chip.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 16 * scale)
                }
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }.apply { addView(row) }
        host.surface.place(scroll, 339, 181, 617, 48)
        scroll.post {
            selectedButton?.let { chip ->
                if (chip.right > scroll.width) scroll.scrollTo(chip.right - scroll.width, 0)
            }
        }
    }

    private fun myTabs(selected: Int) {
        listOf("요약", "리캡", "정원 도감", "목표").forEachIndexed { i, name ->
            val x = 38 + 237 * i
            button(name, x, 100, 237, 36, "#00000000", if (i == selected) rose else brown) {
                go(listOf("summary", "recap", "garden", "goals")[i])
            }
            if (i == selected) panel(x + 99, 135, 40, 3, rose)
        }
        panel(38, 138, 948, 1, "#e3d7ca")
    }

    private fun goals() {
        label(if (page == "goal_edit") "목표 설정" else "오늘의 목표", 66, 72, 500, 34, 25f, bold = true)
        myTabs(3)
        if (page == "goals") {
            panel(66, 156, 892, 286)
            label(today.format(shortDate), 86, 169, 380, 42, 23f, bold = true)
            goalList(86, 224, 488, 205, 44, green)
            val percent = completion()
            label("오늘 실행률  $percent%", 599, 217, 333, 52, 23f, rose, true)
            panel(599, 264, 333, 8, "#e7e5d9")
            if (percent > 0) panel(599, 264, 333 * percent / 100, 8, "#83996e")
            label(db.goalEnd()?.let { "목표 돌아보기 · ${it.format(shortDate)}" } ?: "목표 기간 · 1주",
                599, 280, 333, 39, 16f)
            button("목표 설정", 599, 353, 333, 44, rose, paper) {
                goalDays = db.duration()
                goalChoice = goalOptions.indexOf(tasks()).coerceAtLeast(0)
                go("goal_edit")
            }
        } else {
            panel(66, 190, 444, 248)
            panel(528, 190, 430, 248)
            label("오늘 실천할 목표를 골라주세요", 85, 200, 401, 33, 18f, bold = true)
            listOf("마음 돌보기", "여유 있게 이동하기", "하루를 기록하기", "목표 추천").forEachIndexed { i, name ->
                button(name, 85, 241 + i * 46, 405, 40, if (i == goalChoice) "#536548" else green,
                    if (i == goalChoice) paper else brown) {
                    goalChoice = if (i < 3) i else when (db.entries().firstOrNull()?.mood) {
                        Mood.WORRY, Mood.TIRED -> 1
                        Mood.JOY, Mood.EXCITED -> 2
                        else -> 0
                    }
                    host.render()
                }
            }
            label("오늘의 목표", 548, 199, 390, 35, 18f, bold = true)
            goalOptions[goalChoice].forEachIndexed { i, task ->
                label(task, 548, 235 + i * 29, 390, 29)
            }
            label("목표를 돌아볼 시점", 548, 323, 390, 23, 14f, muted)
            listOf(7 to "1주", 14 to "2주", 30 to "한 달").forEachIndexed { i, (days, name) ->
                button(name, 548 + i * 130, 346, 121, 29, selected = goalDays == days) {
                    goalDays = days
                    host.render()
                }
            }
            button("취소", 548, 385, 135, 38, "#f1e3db") { go("goals") }
            button("목표 저장", 694, 385, 244, 38, rose, paper) {
                if (db.saveGoal(goalOptions[goalChoice], goalDays)) go("goals")
                else msg("목표를 저장하지 못했어요.")
            }
        }
    }

    private val goalOptions = listOf(
        listOf("내 마음 천천히 전하기", "바쁠 때 한 번 숨 고르기", "오늘 잘한 일 떠올리기"),
        listOf("출발 전 여유 시간 갖기", "정차 후 어깨 풀어주기", "도착하면 숨 고르기"),
        listOf("출발할 때 한 문장 남기기", "고마웠던 순간 떠올리기", "도착하면 일기 남기기")
    )

    private fun distribution(moods: List<Mood>): List<Pair<Mood, Int>> {
        val totals = moods.groupingBy { it }.eachCount().toList()
            .sortedWith(compareByDescending<Pair<Mood, Int>> { it.second }.thenBy { it.first.ordinal })
        val sum = totals.sumOf { it.second }
        if (sum == 0) return emptyList()
        var cumulative = 0
        return totals.map { (mood, total) ->
            val before = cumulative * 100 / sum
            cumulative += total
            mood to (cumulative * 100 / sum - before)
        }
    }

    private fun bars(entries: List<Diary>, x: Int, y: Int, w: Int, step: Int, limit: Int = 6) {
        drawBars(distribution(entries.map { it.emotion }), x, y, w, step, limit)
    }

    private fun drawBars(scores: List<Pair<Mood, Int>>, x: Int, y: Int, w: Int, step: Int, limit: Int = 6) {
        if (scores.isEmpty()) {
            label("감정이 기록되면 여기에 표시돼요.", x, y, w, 37, 14f, muted)
            return
        }
        for ((i, pair) in scores.take(limit).withIndex()) {
            val (mood, percent) = pair
            val top = y + i * step
            label(mood.title, x, top, 42, step, 13f)
            panel(x + 43, top + step / 2 - 5, w - 93, 11, "#eee7da")
            panel(x + 43, top + step / 2 - 5, maxOf(1, (w - 93) * percent / 100), 11, mood.color)
            label("$percent%", x + w - 40, top, 40, step, 13f)
        }
    }

    private fun moveMonth(step: Long) {
        month = month.plusMonths(step)
        half = (month.monthValue - 1) / 6
        host.render()
    }

    private fun monthTitle(y: Int = 70) {
        button("‹", 344, y, 44, 31, paper) { moveMonth(-1) }
        label("${month.year}년 ${month.monthValue}월", 390, y, 244, 32, 20f, bold = true, center = true)
        button("›", 636, y, 44, 31, paper) { moveMonth(1) }
    }

    private fun calendar(entries: List<Diary>, x: Int, y: Int, width: Int, step: Int, returnTo: String) {
        val days = entries.groupBy { it.date.dayOfMonth }
        val cell = width / 7
        listOf("일", "월", "화", "수", "목", "금", "토").forEachIndexed { i, name ->
            label(name, x + i * cell, y, cell, 23, 12f, muted, center = true)
        }
        val offset = month.atDay(1).dayOfWeek.value % 7
        for (day in 1..month.lengthOfMonth()) {
            val index = offset + day - 1
            val left = x + index % 7 * cell + 5
            val top = y + 26 + index / 7 * step
            val records = days[day].orEmpty()
            val bg = records.firstOrNull()?.mood?.color ?: "#f0e9dc"
            if (records.isEmpty()) {
                panel(left, top, cell - 10, step - 4, bg)
                label(day.toString(), left, top, cell - 10, step - 4, 14f, center = true)
            } else button(day.toString(), left, top, cell - 10, step - 4, bg) {
                selectDay(records, returnTo)
            }.contentDescription = "${day}일 일기 보기"
        }
    }

    private fun overview() {
        val entries = db.month(month)
        if (page == "streak") {
            myTabs(0)
            panel(165, 186, 687, 197)
            label("${month.year}년 ${month.monthValue}월", 185, 195, 500, 32, 20f, bold = true)
            val recordedDays = entries.map { it.date.dayOfMonth }.toSet()
            for (day in 1..month.lengthOfMonth()) {
                val x = 185 + (day - 1) % 7 * 93
                val y = 230 + (day - 1) / 7 * 28
                if (day in recordedDays) button("$day ✓", x, y, 87, 25, green) {
                    selectDay(entries.filter { it.date.dayOfMonth == day }, "streak")
                } else label(day.toString(), x, y, 87, 25, 14f, muted, center = true)
            }
            label(db.streak().toString(), 850, 210, 140, 62, 47f, rose, true, center = true)
            label("일 연속 작성", 850, 275, 140, 34, 16f, muted, center = true)
            monthTitle(68)
            return
        }
        val flowers = entries.filter { it.flower != null }
        val top = if (page == "recap") null else distribution(entries.map { it.emotion }).firstOrNull()
        monthTitle()
        if (page != "month_summary") myTabs(if (page == "recap") 1 else 0)
        when (page) {
            "recap" -> {
                val earned = flowers.filterNot { it.isDemo }
                val samples = db.demoFlowers(month)
                val recapFlowers = earned + samples
                val moods = recapFlowers.mapNotNull { it.flower }
                panel(38, 155, 465, 294)
                panel(519, 155, 467, 294)
                label("${month.monthValue}월 꽃다발", 59, 169, 424, 41, 23f, bold = true)
                label("내 꽃 ${earned.size}송이 · 시연 ${samples.size}송이",
                    223, 173, 253, 33, 14f, muted, center = true)
                if (recapFlowers.isEmpty()) label("이달에 피어난 꽃이 없어요.", 58, 218, 424, 137, 18f, center = true)
                else {
                    val bouquet = moods.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
                    bouquet.forEachIndexed { index, (mood, count) ->
                        val x = 101 + (index % 3) * 116
                        val y = 214 + (index / 3) * 72
                        art("flower${mood.art}", x, y, 86, 62, mood.flower)
                        label("${count}송이", x + 56, y + 34, 57, 24, 13f, muted)
                    }
                    label("꽃이 피어난 날 ${recapFlowers.map { it.date }.distinct().size}일",
                        59, 371, 424, 31, 14f, center = true)
                }
                button("내 정원 보기", 60, 407, 423, 36) { go("garden") }
                label("꽃으로 본 감정 비율", 540, 171, 418, 39, 23f, bold = true)
                drawBars(distribution(moods), 541, 217, 417, 33)
            }
            "month_summary" -> {
                button("‹ 마이페이지", 38, 82, 194, 34) { go("summary") }
                panel(38, 142, 445, 300)
                panel(497, 142, 489, 300)
                label("하루하루의 감정", 57, 151, 406, 38, 21f, bold = true)
                calendar(entries, 55, 192, 410, 36, page)
                label("가장 많이 느낀 감정",
                    517, 151, 448, 52, 22f, bold = true)
                label(top?.let { "${it.first.title} ${it.second}%" } ?: "기록을 하나씩 쌓아보세요.",
                    517, 206, 448, 32, 24f, rose, true)
                bars(entries, 519, 247, 435, 25)
                button("${month.monthValue}월 꽃다발 보기 ›", 517, 404, 448, 33) { go("recap") }
            }
            else -> {
                val tiles = listOf("${entries.map { it.date }.distinct().size}편" to "작성한 일기",
                    "${db.streak()}일" to "연속 기록", "${completion()}%" to "오늘 목표 실행률",
                    "${flowers.map { it.date }.distinct().size}송이" to "모은 꽃")
                tiles.forEachIndexed { i, (value, caption) ->
                    val x = 38 + i * 240
                    panel(x, 148, 228, 60)
                    label(value, x + 20, 149, 180, 34, 24f, "#536548", true)
                    label(caption, x + 20, 181, 190, 22, 13f)
                    hit("$caption $value", x, 148, 228, 60) {
                        go(listOf("month_summary", "streak", "goals", "recap")[i])
                    }
                }
                panel(38, 218, 343, 237)
                panel(390, 218, 266, 237)
                panel(665, 218, 321, 237)
                label("하루하루의 감정", 52, 223, 300, 29, 16f, bold = true)
                calendar(entries, 47, 252, 321, 28, "summary")
                label("가장 많이 느낀 감정", 406, 223, 235, 29, 16f, bold = true)
                label(top?.let { "${it.first.title} ${it.second}%" } ?: "아직 기록이 없어요.",
                    406, 249, 233, 42, 23f, rose, true)
                bars(entries, 406, 292, 233, 24)
                label("마음꽃이 전하는 말", 680, 223, 290, 32, 16f, "#536548", true)
                panel(680, 256, 291, 56, "#f1e4de")
                label(top?.first?.message ?: "오늘의 마음을 한 문장으로 남겨보세요.", 692, 256, 267, 56,
                    14f, center = true)
                art(if (flowers.isEmpty()) "morning" else "evening", 770, 312, 106, 106)
                button("${month.monthValue}월 꽃다발 보러 가기 ›", 680, 418, 290, 28) { go("recap") }
            }
        }
    }

    private fun garden() {
        val entries = db.entries().filter { it.flower != null && !it.isDemo }
        if (page == "flower") {
            button("‹ 돌아가기", 45, 82, 171, 42, paper) { go(flowerBack) }
            panel(45, 140, 339, 305, green)
            art(characterArt(flower), 60, 141, 309, 219, flower.flower)
            label(flower.flower, 45, 350, 339, 55, 27f, bold = true, center = true)
            panel(406, 140, 572, 305)
            label("꽃말 · ${flower.meaning}", 425, 168, 535, 55, 23f, rose, true)
            label("감정 · ${flower.title}     전체 피어난 횟수 · ${entries.count { it.flower == flower }}회",
                425, 225, 535, 46, 17f)
            label(flower.message, 425, 276, 535, 65, 19f)
            button("관련 일기 보기", 425, 365, 535, 47) {
                val records = entries.filter { it.flower == flower }
                if (records.isNotEmpty()) selectDay(records, "flower") else msg("이 꽃을 피운 일기가 없어요.")
            }
            return
        }
        if (page == "garden" || page == "bed") {
            label("나의 정원", 66, 69, 420, 34, 25f, bold = true)
            label("${entries.size}송이", 550, 69, 190, 31, 16f, muted, center = true)
            button("꽃도감 ›", 771, 69, 190, 31) { go("flowers") }
            myTabs(2)
            gardenBackground()
            if (entries.isEmpty()) {
                label("일기를 쓰고 마음꽃을 피우면 여기에 자라요.", 142, 228, 740, 133, 21f, center = true)
            } else {
                gardenFlowers(entries.sortedWith(compareBy<Diary> { it.date }.thenBy { it.time }.thenBy { it.id }))
            }
            return
        }
        button("‹ 정원으로", 45, 81, 171, 38, paper) { go("garden") }
        label("꽃도감", 405, 76, 214, 45, 25f, bold = true, center = true)
        Mood.entries.forEachIndexed { i, value ->
            val x = 47 + i % 3 * 313
            val y = 142 + i / 3 * 146
            panel(x, y, 300, 131, paper)
            art("flower${value.art}", x + 16, y + 14, 83, 97)
            label(value.flower, x + 113, y + 27, 170, 34, 20f, bold = true)
            label("꽃말 · ${value.meaning}", x + 113, y + 65, 170, 33, 15f, muted)
            hit(value.flower, x, y, 300, 131) { openFlower(value, "flowers") }
        }
    }

    private fun gardenFlowers(entries: List<Diary>) {
        val columns = 9
        val rows = 3
        val slotsPerPatch = columns * rows
        val slots = (0 until slotsPerPatch).shuffled(Random(231))
        val flowerViews = entries.mapIndexedNotNull { index, entry ->
            val bloom = entry.flower ?: return@mapIndexedNotNull null
            val slot = slots[index % slotsPerPatch]
            val jitter = Random(entry.id.hashCode())
            val x = slot % columns * 89 + jitter.nextInt(0, 17)
            val y = (index / slotsPerPatch * rows + slot / columns) * 76 + jitter.nextInt(0, 9)
            imageView("flower${bloom.art}", "${bloom.flower} · ${entry.date.format(shortDate)}")?.apply {
                setOnClickListener { openFlower(bloom, "garden") }
                isFocusable = true
            }?.let { Triple(it, x, y) }
        }
        val field = object : FrameLayout(host) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val scale = MeasureSpec.getSize(widthMeasureSpec) / 810f
                flowerViews.forEach { (view, x, y) ->
                    val params = view.layoutParams as LayoutParams
                    params.width = (70 * scale).toInt()
                    params.height = (66 * scale).toInt()
                    params.leftMargin = (x * scale).toInt()
                    params.topMargin = (y * scale).toInt()
                }
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }
        flowerViews.forEach { (view, _, _) -> field.addView(view, FrameLayout.LayoutParams(0, 0)) }
        val scroll = ScrollView(host).apply {
            addView(field, FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        host.surface.place(scroll, 107, 178, 810, 239)
    }

    private fun gardenBackground(top: Int = 158) {
        art("garden_base", 38.4, top, 947.2, 435 - top)
        art("garden_banks", 38.4, top + 8.3, 947.2, 426.7 - top)
        art("garden_path", 110.1, top + 21.3, 875.5, 413.7 - top)
        art("garden_grass", 150.6, top + 77.2, 751.2, 315.7 - top)
    }
}
