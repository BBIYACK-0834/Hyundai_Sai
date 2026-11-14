package kr.co.sai.diary

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.PictureDrawable
import android.os.Bundle
import android.text.InputFilter
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.EditText
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
    private var latestSavedId: String? = state?.getString("journal_latest_saved")
    private val pictures = mutableMapOf<String, Drawable.ConstantState>()
    private val files = host.assets.list("art").orEmpty().associateBy { it.substringBeforeLast('.') }
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
        out.putInt("journal_flower", flower.ordinal)
        out.putString("journal_flower_back", flowerBack)
    }

    fun go(next: String) {
        if (next != page) cancelRecording()
        if (next != page) stopMusic()
        page = next
        host.render()
    }

    fun back(): Boolean {
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

    fun clear(): Boolean {
        cancelSpeech()
        stopMusic()
        voiceDraft = null
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
        val filename = files[name] ?: return
        val drawable = pictures[name]?.newDrawable() ?: host.assets.open("art/$filename").use {
            if (filename.endsWith(".svg")) PictureDrawable(SVG.getFromInputStream(it).renderToPicture())
            else Drawable.createFromStream(it, filename)
        } ?: return
        drawable.constantState?.let { pictures[name] = it }
        val view = ImageView(host).apply {
            setImageDrawable(drawable)
            if (filename.endsWith(".svg")) setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = description
            if (description == null) importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        host.surface.place(view, x, y, w, h)
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
        val onDark = page == "home" && night
        val text = if (onDark) paper else brown
        label(if (page == "drive") "주행 화면 시연" else if (page == "home") today.format(dateFormat) else "",
            38, 15, 550, 40, 18f, text)
        panel(630, 16, 356, 39)
        panel(635, 20, 37, 31, if (page == "drive") "#f7edce" else green)
        label(if (page == "drive") "D" else "P", 635, 20, 37, 31, 17f, bold = true, center = true)
        art("fuel", 690, 26, 17, 18)
        label("—", 715, 17, 44, 37)
        val icon = if (night) listOf("moon", "night_cloud", "night_rain", "night_snow")[sky]
            else listOf("sun", "cloud", "rain", "snow")[sky]
        art(icon, 781, 26, 18, 18)
        label(weather.substringAfter(' '), 806, 17, 56, 37)
        art("clock", 872, 26, 18, 18)
        label(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")), 898, 17, 77, 37)
        panel(38, 62, 948, 1, "#e3d7cb")
        if (page == "home") {
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
    private fun completion(): Int = tasks().count { it in db.checked() } * 100 / tasks().size

    private fun home() {
        val date = if (night) today else today.minusDays(1)
        val entries = db.entries().filter { it.date == date }
        val recent = entries.firstOrNull()
        val todayFlower = db.entries().firstOrNull { it.date == today && it.flower != null }?.flower
        notebook(38)
        notebook(706)
        label(if (night) "오늘의 일기" else "어제의 일기", 60, 104, 235, 28, 14f, "#536548", true)
        label(date.format(shortDate), 59, 138, 240, 30, 20f, bold = true)
        panel(59, 176, 239, 45, "#f3ebdd")
        label(recent?.summary ?: "아직 남긴 일기가 없어요.", 68, 176, 219, 45, 15f)
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
        label(message.ifBlank { if (night) "오늘은 어떤 하루였나요?" else "어떤 마음으로\n출발할까요?" },
            365, 100, 294, 74, 21f, bold = true, center = true)
        art(if (night && todayFlower != null) "flower${todayFlower.art}" else "morning", 409.6, 181.6, 204.8, 204.8, "마음꽃")
        panel(481, 340, 62, 59, "#eaccc4")
        panel(487, 345, 50, 50, "#a46057")
        if (speechPhase == SpeechPhase.RECORDING) {
            label("Ⅱ", 487, 345, 50, 49, 23f, paper, true, center = true)
        } else {
            art("mic", 506, 358, 12, 18)
        }
        val caption = when (speechPhase) {
            SpeechPhase.STARTING -> "마이크 준비 중…"
            SpeechPhase.RECORDING -> "일시정지"
            SpeechPhase.TRANSCRIBING -> "텍스트 저장 중…"
            null -> if (voiceDraft != null) "다시 저장" else "일기 녹음"
        }
        panel(428, 399, 168, 27, "#fff5ea")
        label(caption, 428, 399, 168, 27, 14f, center = true)
        if (speechPhase == SpeechPhase.RECORDING || speechPhase == null) {
            hit(caption, 428, 340, 168, 86) { handleVoiceControl() }
        }
        val latestSaved = db.entries().firstOrNull { it.id == latestSavedId }
        if (speechPhase == null && voiceDraft == null && latestSaved != null) {
            button("방금 저장한 일기 보기 ›", 372, 430, 280, 31, paper) { openDiary(latestSaved, "home") }
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
            tasks().take(3).forEachIndexed { i, task ->
                button((if (task in db.checked()) "✓ " else "□ ") + task, 727, 139 + i * 33, 238, 32, paper) {
                    if (db.toggle(task)) host.render() else msg("목표를 저장하지 못했어요.")
                }
            }
            button("목표 살펴보기", 727, 245, 238, 37, rose, paper) { go("goals") }
            panel(727, 296, 238, 1, "#e6dbce")
            label("마음에 어울리는 음악", 727, 303, 238, 30, 15f, bold = true)
            label("햇살을 닮은 멜로디", 727, 349, 238, 40)
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

    private fun handleVoiceControl() {
        if (speechPhase == SpeechPhase.RECORDING) {
            speechPhase = SpeechPhase.TRANSCRIBING
            message = "음성을 글로 바꿔 저장하고 있어요."
            speech?.finish()
            host.render()
            return
        }
        voiceDraft?.let { saveVoiceDiary(it); return }
        if (microphonePermissionPending) return
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
        if (granted) startSpeech() else editDiary(null, "마이크 권한 없이 글로 기록할 수 있어요.")
    }

    private fun startSpeech() {
        val recordedAt = LocalDateTime.now()
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
                message = "음성을 글로 바꿔 저장하고 있어요."
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

    private fun saveVoiceDiary(draft: VoiceDraft) {
        val entry = db.add(draft.text, draft.moment, voice = true, recordedAt = draft.recordedAt, id = draft.id)
        voiceDraft = if (entry == null) draft else null
        latestSavedId = entry?.id ?: latestSavedId
        message = if (entry == null) "홈에서 다시 저장해 주세요." else "일기를 저장했어요."
        if (!host.isJournalVisible || page != "home") msg(message)
        host.render()
    }

    private fun saveTypedDiary(body: String) {
        val entry = db.add(body, if (night) "도착" else "출발", voice = false)
        if (entry == null) {
            editDiary(null, "저장하지 못했어요. 다시 시도해 주세요.", body)
            return
        }
        latestSavedId = entry.id
        message = "일기를 저장했어요."
        host.render()
    }

    private fun editDiary(entry: Diary?, reason: String? = null, draft: String = entry?.body.orEmpty()) {
        val input = EditText(host).apply {
            setText(draft)
            hint = "오늘의 마음을 적어주세요"
            minLines = 3
            gravity = Gravity.TOP
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            filters = arrayOf(InputFilter.LengthFilter(12000))
        }
        val dialog = AlertDialog.Builder(host).setTitle(if (entry == null) "일기 남기기" else "일기 수정")
            .setMessage(reason).setView(input).setNegativeButton("취소", null)
            .setPositiveButton("저장", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = input.text.toString().trim()
                if (text.isBlank()) { input.error = "내용을 입력해 주세요."; return@setOnClickListener }
                if (entry == null) {
                    dialog.dismiss()
                    saveTypedDiary(text)
                } else if (db.replace(entry.copy(body = text))) {
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
        go("detail")
    }

    private fun openFlower(mood: Mood, returnTo: String) {
        flower = mood
        flowerBack = returnTo
        go("flower")
    }

    companion object {
        private val dateFormat = DateTimeFormatter.ofPattern("yyyy년 M월 d일 EEEE", Locale.KOREAN)
        private val shortDate = DateTimeFormatter.ofPattern("M월 d일 EEEE", Locale.KOREAN)
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
        button("${month.year} ﹀", 424, 81, 181, 44, paper) { go("years") }
        button("날짜별", 772, 88, 185, 38) { openMonth(month, if (month == YearMonth.now()) (today.dayOfMonth - 1) / 6 else 0) }
        if (db.entries().none { it.date.year == month.year } && month.year != today.year) {
            label("이 해에는 아직 남긴 일기가 없어요.", 130, 208, 764, 110, 24f, center = true)
            return
        }
        val covers = listOf("#e9cfc7", "#dbe4cf", "#efe0b6", "#e3d9ed", "#ead8bc", "#d4e2e1")
        val spines = listOf("#bd8478", "#889a70", "#c8a04e", "#a492bc", "#bc956b", "#819fa0")
        val entriesByMonth = db.entries().filter { it.date.year == month.year }.groupBy { it.date.monthValue }
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
            val coverFlower = entries.firstNotNullOfOrNull { it.mood }
            if (coverFlower != null) art("flower${coverFlower.art}", x + 27, y + 8, 94, 84)
            label(if (entries.isEmpty()) "기록 없음" else "${entries.size}편",
                x + 20, y + 91, 104, 28, 13f, center = true)
            label("${m}월", x, y + 136, 124, 28, 17f, center = true)
            hit("${m}월 일기장", x - 6, y, 144, 156) { openMonth(YearMonth.of(month.year, m)) }
        }
        if (half == 1) button("‹", 38, 264, 48, 48) { half = 0; host.render() }
        if (half == 0) button("›", 938, 264, 48, 48) { half = 1; host.render() }
    }

    private fun years() {
        button("‹ 일기장", 45, 82, 169, 42, paper) { go("books") }
        label("연도 선택", 312, 82, 400, 50, 25f, bold = true, center = true)
        val lowest = minOf(today.year - 4, db.entries().minOfOrNull { it.date.year } ?: today.year)
        if (month.year > lowest) button("‹", 302, 224, 54, 49) { month = month.minusYears(1); host.render() }
        button("${month.year}년", 365, 207, 294, 81, green, brown, true) { go("books") }
        if (month.year <= today.year) button("›", 669, 224, 54, 49) { month = month.plusYears(1); host.render() }
        label("연도를 선택하여 일기장을 열어요.", 248, 311, 528, 59, 17f, muted, center = true)
    }

    private fun openMonth(value: YearMonth, index: Int = 0) {
        month = value
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
            label(entries.mapNotNull { it.mood?.title }.distinct().joinToString(" · "), x + 14, y + 72, 210, 20, 13f, muted)
            if (entry == null) {
                val sprout = ImageView(host).apply {
                    setImageDrawable(host.assets.open("sprout.svg").use { PictureDrawable(SVG.getFromInputStream(it).renderToPicture()) })
                    setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = "아직 피지 않은 새싹"
                }
                host.surface.place(sprout, x + 222, y + 19, 50, 58)
            } else {
                val mood = entries.firstNotNullOfOrNull { it.mood }
                art(mood?.let { "flower${it.art}" } ?: "morning", x + 219, y + 12, 63, 66)
                hit("${month.monthValue}월 ${day}일 일기 보기", x, y, 289, 99) { selectDay(entries, "dates") }
            }
        }
        if (dayPage > 0) button("‹ 이전 날짜", 139, 391, 164, 36) { dayPage--; host.render() }
        label("$first–${last}일  ·  ${dayPage + 1}/${(month.lengthOfMonth() + 5) / 6}", 360, 389, 304, 42, 17f, muted, center = true)
        if (last < month.lengthOfMonth()) button("다음 날짜 ›", 720, 391, 164, 36) { dayPage++; host.render() }
    }

    private fun selectDay(entries: List<Diary>, returnTo: String) {
        if (entries.size == 1) openDiary(entries.first(), returnTo)
        else AlertDialog.Builder(host).setTitle("일기 선택")
            .setItems(entries.map { "${it.time.format(DateTimeFormatter.ofPattern("HH:mm"))}  ${it.moment} · ${it.summary}" }.toTypedArray()) { _, i ->
                openDiary(entries[i], returnTo)
            }.show()
    }

    private fun detail() {
        button("‹ 날짜별 목차", 45, 81, 169, 42, paper) { go(detailBack) }
        val entry = db.entries().firstOrNull { it.id == detailId }
        if (entry == null) {
            label("일기를 찾을 수 없어요.", 220, 190, 584, 90, 23f, center = true)
            return
        }
        panel(47, 137, 344, 308, "#f0e9d9")
        panel(398, 137, 582, 308)
        label(entry.date.format(shortDate), 67, 154, 304, 46, 23f, bold = true)
        art(entry.flower?.let { "flower${it.art}" } ?: "morning", 110, 186, 197, 198)
        label(entry.flower?.let { "${it.flower} · ${it.meaning}" } ?: "${entry.moment} 일기",
            65, 382, 310, 43, 19f, center = true)
        listOf("요약 일기", "시간별 기록", "음성 기록").forEachIndexed { i, value ->
            button(value, 684 + i * 97, 84, 91, 35, if (detailTab == i) green else paper) {
                detailTab = i
                host.render()
            }
        }
        val content = when (detailTab) {
            0 -> entry.body
            1 -> db.entries().filter { it.date == entry.date }.sortedBy { it.time }
                .joinToString("\n\n") { "${it.time.format(DateTimeFormatter.ofPattern("HH:mm"))}  ${it.moment}\n${it.body}" }
            else -> if (entry.voice) entry.body else "음성으로 작성한 기록이 없어요."
        }
        val body = android.widget.TextView(host).apply {
            text = content
            textSize = 17f
            setTextColor(Color.parseColor(brown))
            setLineSpacing(5f, 1f)
        }
        val scroll = ScrollView(host).apply { addView(body) }
        host.surface.place(scroll, 421, 163, 536, 214)
        button("수정", 421, 390, 92, 39) { editDiary(entry) }
        if (entry.moment == "도착") button("꽃 선택", 524, 390, 123, 39) { chooseFlower(entry) }
        button("삭제", 854, 390, 103, 39, "#f1dfd9") {
            AlertDialog.Builder(host).setTitle("이 일기를 삭제할까요?")
                .setNegativeButton("취소", null).setPositiveButton("삭제") { _, _ ->
                    if (db.delete(entry.id)) go(detailBack) else msg("일기를 삭제하지 못했어요.")
                }.show()
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
            tasks().forEachIndexed { i, task ->
                button((if (task in db.checked()) "✓  " else "□  ") + task, 86, 224 + i * 51, 488, 44, green) {
                    if (db.toggle(task)) host.render() else msg("목표를 저장하지 못했어요.")
                }
            }
            label("오늘 실행률  ${completion()}%", 599, 217, 333, 52, 23f, rose, true)
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

    private fun distribution(entries: List<Diary>): List<Pair<Mood, Int>> {
        val totals = Mood.entries.map { mood ->
            mood to entries.sumOf { entry ->
                entry.flower?.let { if (it == mood) 1 else 0 } ?: (Mood.scores(entry.body)[mood] ?: 0)
            }
        }.filter { it.second > 0 }.sortedByDescending { it.second }
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
        val scores = distribution(entries)
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
            val bg = records.firstOrNull()?.mood?.color ?: if (records.isEmpty()) "#f0e9dc" else green
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
        val flowers = entries.filter { it.flower != null }
        val top = distribution(entries).firstOrNull()
        monthTitle()
        if (page != "month_summary") myTabs(if (page == "recap") 1 else 0)
        when (page) {
            "recap" -> {
                panel(38, 155, 465, 294)
                panel(519, 155, 467, 294)
                label("${month.monthValue}월 꽃다발", 59, 169, 424, 41, 23f, bold = true)
                if (flowers.isEmpty()) label("이달에 피어난 꽃이 없어요.", 58, 218, 424, 137, 18f, center = true)
                else {
                    art("bouquet", 106, 211, 302, 165, "마음 꽃다발")
                    label("이달에 ${flowers.map { it.date }.distinct().size}일 동안 꽃을 피웠어요.",
                        59, 371, 424, 31, 14f, center = true)
                }
                button("월별 꽃 전체 보기", 60, 407, 423, 36) { go("bed") }
                label("감정 비율", 540, 171, 418, 39, 23f, bold = true)
                bars(entries, 541, 217, 417, 33)
            }
            "streak", "month_summary" -> {
                if (page == "month_summary") button("‹ 마이페이지", 38, 82, 194, 34) { go("summary") }
                panel(38, 142, 445, 300)
                panel(497, 142, 489, 300)
                label("하루하루의 감정", 57, 151, 406, 38, 21f, bold = true)
                calendar(entries, 55, 192, 410, 36, page)
                label(if (page == "streak") "${db.streak()}일 연속 작성" else "가장 많이 느낀 감정",
                    517, 151, 448, 52, 22f, bold = true)
                label(top?.let { "${it.first.title} ${it.second}%" } ?: "기록을 하나씩 쌓아보세요.",
                    517, 206, 448, 32, 24f, rose, true)
                bars(entries, 519, 247, 435, 25)
                button("${month.monthValue}월 꽃다발 보기 ›", 517, 404, 448, 33) { go("recap") }
            }
            else -> {
                val tiles = listOf("${entries.size}편" to "작성한 일기",
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
        val entries = db.entries().filter { it.date.year == month.year && it.flower != null }
        val monthly = entries.filter { it.date.monthValue == month.monthValue }
        if (page == "flower") {
            button("‹ 돌아가기", 45, 82, 171, 42, paper) { go(flowerBack) }
            panel(45, 140, 339, 305, green)
            art("flower${flower.art}", 123, 165, 183, 175, flower.flower)
            label(flower.flower, 45, 350, 339, 55, 27f, bold = true, center = true)
            panel(406, 140, 572, 305)
            label("꽃말 · ${flower.meaning}", 425, 168, 535, 55, 23f, rose, true)
            label("감정 · ${flower.title}     올해 피어난 횟수 · ${entries.count { it.flower == flower }}회",
                425, 225, 535, 46, 17f)
            label(flower.message, 425, 276, 535, 65, 19f)
            button("관련 일기 보기", 425, 365, 535, 47) {
                val records = entries.filter { it.flower == flower }
                if (records.isNotEmpty()) selectDay(records, "flower") else msg("이 꽃을 피운 일기가 없어요.")
            }
            return
        }
        monthTitle()
        myTabs(2)
        if (page == "garden") {
            art("garden_base", 38.4, 158, 947.2, 277.5)
            art("garden_banks", 38.4, 166.3, 947.2, 269.2)
            art("garden_path", 110.1, 179.3, 875.5, 256.2)
            art("garden_grass", 150.6, 235.2, 751.2, 157.7)
            for (value in 1..12) {
                val x = 72 + ((value - 1) % 6) * 151
                val y = 188 + ((value - 1) / 6) * 105
                val bloom = entries.firstOrNull { it.date.monthValue == value }?.flower
                bloom?.let { art("flower${it.art}", x + 42, y - 16, 51, 46) }
                button("${value}월", x, y + 31, 134, 37, if (bloom == null) paper else green) {
                    month = YearMonth.of(month.year, value)
                    go("bed")
                }
            }
            button("꽃도감", 763, 411, 196, 36) { go("flowers") }
        } else {
            button("‹ 정원", 46, 151, 118, 36, paper) { go("garden") }
            label(if (page == "bed") "${month.monthValue}월에 피어난 꽃" else "꽃도감",
                175, 150, 550, 39, 22f, bold = true)
            if (page == "bed") button("꽃도감", 788, 151, 180, 36) { go("flowers") }
            val source = if (page == "bed") monthly else entries
            val moods = if (page == "bed") monthly.mapNotNull { it.flower }.distinct() else Mood.entries.toList()
            if (moods.isEmpty()) label("아직 피어난 꽃이 없어요.", 142, 201, 740, 163, 21f, center = true)
            moods.forEachIndexed { i, value ->
                val x = 47 + i % 3 * 313
                val y = 196 + i / 3 * 119
                panel(x, y, 300, 107, paper)
                art("flower${value.art}", x + 15, y + 12, 72, 81)
                label(value.flower, x + 104, y + 16, 180, 37, 20f, bold = true)
                label("${source.count { it.flower == value }}회 피었어요", x + 104, y + 53, 180, 33, 15f, muted)
                hit(value.flower, x, y, 300, 107) { openFlower(value, page) }
            }
        }
    }
}
