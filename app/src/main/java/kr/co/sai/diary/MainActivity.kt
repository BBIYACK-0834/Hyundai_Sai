package kr.co.sai.diary

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var store: ProfileStore
    private lateinit var surface: DesignSurface
    private var profile = Profile()
    private var screen = "profile"
    private val brown = "#4b372f"
    private val green = "#e9efdf"
    private val rose = "#9c625b"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        store = ProfileStore(this)
        profile = store.load()
        if (savedInstanceState != null) {
            profile = Profile(savedInstanceState.getString("nickname", profile.nickname),
                savedInstanceState.getString("moment", profile.moment),
                savedInstanceState.getString("age", profile.age),
                savedInstanceState.getString("gender", profile.gender),
                savedInstanceState.getString("goal", profile.goal))
        }
        screen = savedInstanceState?.getString("screen") ?: when {
            !store.hasProfile() -> "profile"
            store.plantedToday() -> "weather"
            else -> "seed"
        }
        render()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putString("screen", screen)
        out.putString("nickname", profile.nickname); out.putString("moment", profile.moment)
        out.putString("age", profile.age); out.putString("gender", profile.gender)
        out.putString("goal", profile.goal)
    }

    private fun rounded(color: String, selected: Boolean = false) = GradientDrawable().apply {
        setColor(Color.parseColor(color)); cornerRadius = 12 * resources.displayMetrics.density
        if (selected) setStroke((2 * resources.displayMetrics.density).toInt(), Color.parseColor(rose))
    }

    private fun label(value: String, x: Number, y: Number, w: Number, h: Number,
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

    private fun button(value: String, x: Number, y: Number, w: Number, h: Number,
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

    private fun panel(x: Number, y: Number, w: Number, h: Number, color: String) {
        surface.place(View(this).apply { background = rounded(color) }, x, y, w, h)
    }

    private fun render() {
        surface = DesignSurface(this)
        surface.setBackgroundColor(Color.parseColor(if (screen == "weather") "#fcf6ed" else "#fff9f0"))
        setContentView(surface)
        when (screen) { "profile" -> profileScreen(); "seed" -> seedScreen(); else -> weatherScreen() }
    }

    private fun profileScreen() {
        panel(38.4, 88.4, 277.6, 366, green)
        label("사이", 38.4, 110.4, 277.6, 43.2, 28f, "#536548", true).gravity = Gravity.CENTER
        panel(55.6, 172.4, 243.2, 66, "#fffefa")
        label("반가워요.\n오늘의 마음을 들려주세요.", 63.6, 177.2, 227.2, 56.4, 17.6f, "#536548").gravity = Gravity.CENTER
        val sprout = ImageView(this).apply {
            setImageDrawable(android.graphics.drawable.PictureDrawable(com.caverock.androidsvg.SVG.getFromAsset(assets, "sprout.svg").renderToPicture()))
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "기록을 기다리는 새싹"
        }
        surface.place(sprout, 76.76, 255.2, 200.88, 187.92)
        label("내 정보와 첫 목표", 344.8, 56, 472, 40.4, 29.6f, bold = true)
        label("호칭, 연령대, 성별을 고르고 첫 목표를 정해요.", 347.2, 98.4, 600, 26.4, 17.6f, "#826e62")
        panel(344.8, 136, 640.8, 239.6, "#fffefa")
        val rows = listOf(
            Triple("호칭", listOf("친구", "정원사", "운전자"), profile.nickname),
            Triple("기록하고 싶은 순간", listOf("출발할 때", "도착했을 때", "하루의 시작과 끝"), profile.moment),
            Triple("연령대 · 선택", listOf("10대", "20대", "30대", "40대 이상"), profile.age),
            Triple("성별 · 선택", listOf("여성", "남성", "선택 안 함"), profile.gender),
            Triple("첫 목표를 골라주세요", listOf("하루 끝에 일기 말하기", "한 번 깊게 숨 고르기", "고마운 순간 떠올리기"), profile.goal)
        )
        rows.forEachIndexed { row, (title, values, current) ->
            val y = 147.6 + row * 43.2
            label(title, 361.6, y, 142, 34, 14f)
            val width = if (row == 2) 107.6 else 146.8
            values.forEachIndexed { i, value ->
                button(value, 512.8 + i * (width + 6.4), y, width, 33.6,
                    if (row == 4) 13.2f else 15.2f,
                    if (row == 4) "#f2e3de" else green,
                    if (row == 4) rose else "#58674b", current == value) {
                    profile = when (row) {
                        0 -> profile.copy(nickname = value)
                        1 -> profile.copy(moment = value)
                        2 -> profile.copy(age = if (profile.age == value) "선택 안 함" else value)
                        3 -> profile.copy(gender = value)
                        else -> profile.copy(goal = value)
                    }; render()
                }
            }
        }
        panel(344.8, 383.6, 640.8, 36.8, "#f3eddf")
        listOf(Triple("호칭", profile.nickname, 356f), Triple("연령대", profile.age, 440.8f),
            Triple("성별", profile.gender, 540.8f), Triple("첫 목표", profile.goal, 634.8f)).forEach { (title, value, x) ->
            val width = if (title == "첫 목표") 320 else 84
            label(title, x, 384.8, width, 15.6, 10.4f, "#756258")
            label(value, x, 398.8, width, 19.2, 13.2f)
        }
        button("저장하고 씨앗 심기  ›", 680, 430, 305.6, 40.8, bg = rose, fg = "#ffffff") {
            AlertDialog.Builder(this).setTitle("이 기기에 저장할까요?")
                .setMessage("선택한 정보와 첫 목표를 이 기기에 저장해요. 외부로 전송하지 않으며, 정보 관리에서 모두 삭제할 수 있어요.")
                .setNegativeButton("취소", null).setPositiveButton("동의하고 저장") { _, _ ->
                    if (store.save(profile)) { screen = "seed"; render() }
                    else Toast.makeText(this, "저장하지 못했어요. 다시 시도해 주세요.", Toast.LENGTH_LONG).show()
                }.show()
        }
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
        if (store.plant()) { screen = "weather"; render() }
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
                    if (store.setWeather(value)) { render(); Toast.makeText(this, "$value 선택됨", Toast.LENGTH_SHORT).show() }
                    else Toast.makeText(this, "설정을 저장하지 못했어요.", Toast.LENGTH_LONG).show()
                }
            }
        }
        // Management is accessible through Android Back, preserving the Figma canvas.
    }

    @Deprecated("Platform back callback retained for API 28 compatibility")
    override fun onBackPressed() {
        if (screen == "profile") { if (store.hasProfile()) { profile = store.load(); screen = "seed"; render() } else finish(); return }
        AlertDialog.Builder(this).setTitle("정보 관리")
            .setItems(arrayOf("내 정보와 첫 목표 수정", "씨앗 화면 보기", "저장한 정보 모두 삭제", "앱 종료")) { _, item ->
                when (item) {
                    0 -> { profile = store.load(); screen = "profile"; render() }
                    1 -> { screen = "seed"; render() }
                    2 -> AlertDialog.Builder(this).setTitle("저장한 정보를 삭제할까요?")
                        .setMessage("호칭, 기록할 순간, 연령대, 성별, 목표와 씨앗·날씨 선택이 삭제돼요.")
                        .setNegativeButton("취소", null).setPositiveButton("삭제") { _, _ ->
                            if (store.clear()) { profile = Profile(); screen = "profile"; render() }
                            else Toast.makeText(this, "삭제하지 못했어요.", Toast.LENGTH_LONG).show()
                        }.show()
                    3 -> finish()
                }
            }.setNegativeButton("닫기", null).show()
    }
}
