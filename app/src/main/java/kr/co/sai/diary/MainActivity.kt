package kr.co.sai.diary

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.text.InputFilter
import android.text.InputType
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    internal lateinit var store: ProfileStore
    internal lateinit var surface: DesignSurface
    internal var profile = Profile()
    internal lateinit var journal: JournalScreens
    private var screen = "profile"
    private var profileInputs: List<EditText> = emptyList()
    private val brown = "#4b372f"
    private val green = "#e9efdf"
    private val rose = "#9c625b"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        store = ProfileStore(this)
        journal = JournalScreens(this, savedInstanceState)
        profile = if (store.hasProfile()) store.load() else Profile(nickname = "")
        if (savedInstanceState != null) {
            profile = Profile(savedInstanceState.getString("nickname", profile.nickname),
                savedInstanceState.getString("moment", profile.moment),
                savedInstanceState.getString("age", profile.age),
                savedInstanceState.getString("gender", profile.gender),
                savedInstanceState.getString("goal", profile.goal))
        }
        screen = savedInstanceState?.getString("screen") ?: when {
            !store.hasProfile() -> "profile"
            store.plantedToday() -> if (store.hasWeather()) "journal" else "weather"
            else -> "seed"
        }
        render()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        journal.saveState(out)
        out.putString("screen", screen)
        out.putString("nickname", profileInputs.getOrNull(0)?.text?.toString() ?: profile.nickname)
        out.putString("moment", profile.moment)
        out.putString("age", profileInputs.getOrNull(1)?.text?.toString() ?: profile.age)
        out.putString("gender", profileInputs.getOrNull(2)?.text?.toString() ?: profile.gender)
        out.putString("goal", profile.goal)
    }

    private fun rounded(color: String, selected: Boolean = false) = GradientDrawable().apply {
        setColor(Color.parseColor(color)); cornerRadius = 12 * resources.displayMetrics.density
        if (selected) setStroke((2 * resources.displayMetrics.density).toInt(), Color.parseColor(rose))
    }

    internal fun label(value: String, x: Number, y: Number, w: Number, h: Number,
                      size: Float = 17f, color: String = brown, bold: Boolean = false): TextView {
        val view = TextView(this).apply {
            text = value; setTextColor(Color.parseColor(color)); gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
            setTag(R.id.design_text_size, size)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size)
        }
        surface.place(view, x, y, w, h)
        return view
    }

    internal fun button(value: String, x: Number, y: Number, w: Number, h: Number,
                       size: Float = 16.8f, bg: String = green, fg: String = "#58674b",
                       selected: Boolean = false, action: () -> Unit): Button {
        val view = Button(this).apply {
            text = value; isAllCaps = false; setPadding(0, 0, 0, 0)
            elevation = 0f; stateListAnimator = null
            minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
            includeFontPadding = false; gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.parseColor(fg)); background = rounded(bg, selected)
            setTag(R.id.design_text_size, size)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size)
            isSelected = selected; contentDescription = value + if (selected) ", 선택됨" else ""
            setOnClickListener { action() }
        }
        surface.place(view, x, y, w, h)
        return view
    }

    internal fun panel(x: Number, y: Number, w: Number, h: Number, color: String) {
        surface.place(View(this).apply { background = rounded(color) }, x, y, w, h)
    }

    internal fun render() {
        profileInputs = emptyList()
        surface = DesignSurface(this)
        surface.setBackgroundColor(Color.parseColor(if (screen == "weather") "#fcf6ed" else "#fff9f0"))
        setContentView(surface)
        when (screen) {
            "profile" -> profileScreen()
            "seed" -> seedScreen()
            "weather" -> weatherScreen()
            else -> journal.render()
        }
    }

    private fun profileScreen() {
        surface.isFocusableInTouchMode = true
        surface.requestFocus()
        panel(220.8, 52.4, 582.4, 439.2, "#e8dccd")
        panel(221.6, 53.2, 580.8, 437.6, "#fffefa")
        label("내 정보 입력", 254.4, 76.8, 513.6, 43.2, 27.6f, bold = true)
        label("사이에서 사용할 내 정보를 입력해 주세요.", 255.2, 126, 507.2, 24.8, 16f, "#756258")
        label("호칭", 254.4, 169.6, 513.6, 27.2, 16.8f)
        label("나이", 254.4, 259.2, 160, 27.2, 16.8f)
        label("성별 (선택)", 433.6, 259.2, 334.4, 27.2, 16.8f)

        val nickname = profileInput("호칭", "불리고 싶은 이름을 입력해 주세요", profile.nickname,
            254.4, 203.2, 513.6, 40, InputType.TYPE_CLASS_TEXT)
        // Earlier versions stored age ranges. Keep the stored value if this field is left empty.
        val age = profileInput("나이", "예: 25", profile.age.takeIf { it.toIntOrNull() != null }.orEmpty(),
            254.4, 292.8, 160.0, 3, InputType.TYPE_CLASS_NUMBER)
        val gender = profileInput("성별 (선택)", "직접 입력해 주세요", profile.gender.takeUnless { it == "선택 안 함" }.orEmpty(),
            433.6, 292.8, 334.4, 40, InputType.TYPE_CLASS_TEXT)
        profileInputs = listOf(nickname, age, gender)
        button("다음", 254.4, 404.4, 513.6, 44.4, bg = rose, fg = "#ffffff") {
            val name = nickname.text.toString().trim()
            val ageText = age.text.toString().trim()
            if (name.isEmpty()) {
                nickname.error = "불리고 싶은 이름을 입력해 주세요."
                nickname.requestFocus()
                return@button
            }
            if (ageText.isNotEmpty() && ageText.toIntOrNull()?.let { it in 1..130 } != true) {
                age.error = "1세부터 130세까지 입력해 주세요."
                age.requestFocus()
                return@button
            }
            val updated = profile.copy(
                nickname = name,
                age = ageText.ifEmpty { profile.age.takeIf { it.endsWith("대") || it.endsWith("이상") } ?: "선택 안 함" },
                gender = gender.text.toString().trim().ifEmpty { "선택 안 함" }
            )
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(nickname.windowToken, 0)
            AlertDialog.Builder(this).setTitle("이 기기에 저장할까요?")
                .setMessage("입력한 정보를 이 기기에 저장해요. 정보 관리에서 삭제할 수 있어요.")
                .setNegativeButton("취소", null).setPositiveButton("동의하고 저장") { _, _ ->
                    if (store.save(updated)) {
                        profile = updated
                        screen = "seed"
                        render()
                    } else Toast.makeText(this, "저장하지 못했어요. 다시 시도해 주세요.", Toast.LENGTH_LONG).show()
                }.show()
        }
    }

    private fun profileInput(name: String, hintText: String, value: String, x: Double, y: Double,
                             width: Double, length: Int, type: Int): EditText {
        val input = EditText(this).apply {
            setSingleLine()
            inputType = type
            setPadding(11, 0, 11, 0)
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
            hint = hintText
            contentDescription = name
            setText(value)
            setTextColor(Color.parseColor(brown))
            setHintTextColor(Color.parseColor("#796c61"))
            filters = arrayOf(InputFilter.LengthFilter(length))
            background = rounded("#fffdf8").apply {
                setStroke(1, Color.parseColor("#d9cbbe"))
            }
            setTag(R.id.design_text_size, 16.8f)
        }
        surface.place(input, x, y, width, 44.8)
        return input
    }

    private fun seedScreen() {
        val bag = ImageView(this).apply {
            setImageResource(R.drawable.seed_bag); scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "오늘의 마음을 담을 씨앗 주머니"
            setOnClickListener { plant() }
        }
        surface.place(bag, 163.2, -15.2, 697.6, 518.4)
        button("씨앗 심기", 384, 480, 256, 44.8, 19.2f, "#9c6460", "#fffefa") { plant() }
    }

    private fun plant() {
        if (journal.prepareDemoDay() && store.startDay()) weatherScreenSaved()
        else Toast.makeText(this, "씨앗을 저장하지 못했어요.", Toast.LENGTH_LONG).show()
    }

    private fun weatherScreen() {
        panel(62.4, 116.8, 899.2, 0.8, "#e9ddd2")
        label("낮", 62.4, 166, 80, 49.6, 25.6f, "#5e4c43", true)
        label("밤", 62.4, 282.8, 80, 49.6, 25.6f, "#5e4c43", true)
        val colors = listOf("#e4ecd2", "#e3e9e4", "#d6e5eb", "#ebf2f1", "#514f68", "#515c6c", "#426073", "#576981")
        listOf("낮", "밤").forEachIndexed { row, period ->
            listOf("맑음", "흐림", "비", "눈").forEachIndexed { i, weather ->
                val value = "$period $weather"
                button(weather, 172 + i * 197.2, 159.2 + row * 116.8, 172, 66.4, 24f,
                    colors[row * 4 + i], if (row == 0) "#403c34" else "#fff6eb", store.weather() == value) {
                    if (store.setWeather(value)) weatherScreenSaved()
                    else Toast.makeText(this, "설정을 저장하지 못했어요.", Toast.LENGTH_LONG).show()
                }
            }
        }
        // Management is accessible through Android Back, preserving the Figma canvas.
    }

    internal val isJournalVisible get() = screen == "journal"

    internal fun weatherScreenSaved() {
        screen = "journal"
        journal.go("home")
    }

    internal fun changeWeather() { screen = "weather"; render() }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == 42) journal.permissionResult(results.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED)
    }

    override fun onStop() {
        journal.stopTransientWork()
        super.onStop()
    }

    override fun onDestroy() {
        journal.cancelSpeech()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (screen == "journal") render()
    }

    @Deprecated("Platform back callback retained for API 28 compatibility")
    override fun onBackPressed() {
        if (screen == "journal" && journal.back()) return
        if (screen == "journal") journal.stopTransientWork()
        if (screen == "profile") { if (store.hasProfile()) { profile = store.load(); screen = "seed"; render() } else finish(); return }
        AlertDialog.Builder(this).setTitle("정보 관리")
            .setItems(arrayOf("내 정보 수정", "씨앗 화면 보기", "저장한 정보 모두 삭제", "앱 종료")) { _, item ->
                when (item) {
                    0 -> { profile = store.load(); screen = "profile"; render() }
                    1 -> { screen = "seed"; render() }
                    2 -> AlertDialog.Builder(this).setTitle("저장한 정보를 삭제할까요?")
                        .setMessage("호칭, 기록할 순간, 연령대, 성별, 목표, 일기와 씨앗·날씨 선택이 삭제돼요.")
                        .setNegativeButton("취소", null).setPositiveButton("삭제") { _, _ ->
                            if (journal.clear() && store.clear()) { profile = Profile(nickname = ""); screen = "profile"; render() }
                            else Toast.makeText(this, "삭제하지 못했어요.", Toast.LENGTH_LONG).show()
                        }.show()
                    3 -> finish()
                }
            }.setNegativeButton("닫기", null).show()
    }
}
