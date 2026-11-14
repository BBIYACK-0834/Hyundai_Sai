package kr.co.sai.diary

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.util.UUID

internal enum class Mood(val title: String, val flower: String, val meaning: String, val color: String, val art: Int, val words: List<String>) {
    CALM("평온", "술패랭이", "평정", "#9db885", 0, listOf("편안", "평온", "차분", "고요", "안심")),
    WORRY("걱정", "제비꽃", "마음의 평화", "#b8a1c8", 1, listOf("걱정", "불안", "긴장", "부담", "막막")),
    TIRED("지침", "마삭줄", "다정한 쉼", "#94aeb5", 2, listOf("피곤", "지쳤", "힘들", "지침", "졸려")),
    JOY("기쁨", "프리지아", "새로운 시작", "#ecc76f", 3, listOf("행복", "기쁘", "기뻤", "즐거", "좋았", "기쁨")),
    EXCITED("설렘", "튤립", "설렘", "#e5a5a0", 4, listOf("설레", "설렘", "기대", "두근")),
    SAD("슬픔", "루드베키아", "다시 피어나는 마음", "#a6b5ce", 5, listOf("슬프", "슬펐", "슬픔", "울었", "눈물", "외로"));

    val message: String get() = when (this) {
        CALM -> "차분해진 마음으로 오늘을 천천히 마무리해요."
        WORRY, TIRED, SAD -> "마음이 복잡했던 날엔 잠깐 쉬어도 괜찮아요."
        JOY, EXCITED -> "오늘의 작은 기쁨을 오래 간직해요."
    }

    companion object {
        fun classify(text: String): Mood = scores(text).maxByOrNull { it.value }?.key ?: CALM

        fun scores(text: String): Map<Mood, Int> = entries.associateWith { mood ->
            mood.words.sumOf { word -> Regex(Regex.escape(word)).findAll(text).count() }
        }.filterValues { it > 0 }
    }
}

internal data class Diary(
    val id: String, val date: LocalDate, val time: LocalTime, val body: String,
    val moment: String, val voice: Boolean, val flower: Mood?,
    val emotion: Mood = Mood.classify(body)
) {
    val summary get() = body.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(70)
    val mood get() = flower ?: emotion
}

internal class JournalStore(context: Context) {
    private val prefs = context.getSharedPreferences("sai_journal", Context.MODE_PRIVATE)

    fun entries(): List<Diary> {
        val array = JSONArray(prefs.getString("entries", "[]"))
        return List(array.length()) { i ->
            val entry = array.getJSONObject(i)
            Diary(entry.getString("id"), LocalDate.parse(entry.getString("date")),
                LocalTime.parse(entry.getString("time")), entry.getString("body"),
                entry.getString("moment"), entry.optBoolean("voice"),
                Mood.entries.find { it.name == entry.optString("flower") },
                Mood.entries.find { it.name == entry.optString("emotion") }
                    ?: Mood.classify(entry.getString("body")))
        }.sortedWith(compareByDescending<Diary> { it.date }.thenByDescending { it.time })
    }

    private fun save(entries: List<Diary>): Boolean {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(JSONObject().put("id", entry.id).put("date", entry.date.toString())
                .put("time", entry.time.toString()).put("body", entry.body)
                .put("moment", entry.moment).put("voice", entry.voice)
                .put("flower", entry.flower?.name).put("emotion", entry.emotion.name))
        }
        return prefs.edit().putString("entries", array.toString()).commit()
    }

    fun add(
        body: String, moment: String, voice: Boolean,
        recordedAt: LocalDateTime = LocalDateTime.now(),
        id: String = UUID.randomUUID().toString()
    ): Diary? {
        val entry = Diary(id, recordedAt.toLocalDate(), recordedAt.toLocalTime(), body.trim(), moment, voice, null)
        return entry.takeIf { save(entries().filterNot { it.id == id } + entry) }
    }

    fun replace(entry: Diary) = save(entries().map { if (it.id == entry.id) entry else it })
    fun delete(id: String) = save(entries().filterNot { it.id == id })
    fun month(month: YearMonth) = entries().filter { YearMonth.from(it.date) == month }
    fun streak(): Int {
        val days = entries().map { it.date }.toSet()
        var cursor = LocalDate.now()
        if (cursor !in days) cursor = cursor.minusDays(1)
        var count = 0
        while (cursor in days) {
            count++
            cursor = cursor.minusDays(1)
        }
        return count
    }

    fun seedYesterday(today: LocalDate = LocalDate.now()): Boolean {
        if (prefs.getBoolean("demo_yesterday_seeded", false)) return true
        val yesterday = today.minusDays(1)
        if (entries().none { it.date == yesterday } && add(
                "퇴근길에 노을을 보니 마음이 편안해졌어요. 좋아하는 음악을 들으며 천천히 집에 왔어요.",
                "시연 예시", voice = false, recordedAt = yesterday.atTime(18, 40), id = "demo-yesterday"
            ) == null) return false
        return prefs.edit().putBoolean("demo_yesterday_seeded", true).commit()
    }

    fun bloomToday(today: LocalDate = LocalDate.now(), preferredId: String? = null): Boolean {
        val records = entries().filter { it.date == today && it.id != "demo-yesterday" }
        val entry = records.firstOrNull { it.id == preferredId } ?: records.firstOrNull() ?: return true
        return entry.flower != null || replace(entry.copy(flower = entry.emotion))
    }

    fun saveSpokenGoal(text: String): Boolean {
        val goal = when {
            Regex("운동|헬스|산책|걷|달리|스트레칭").containsMatchIn(text) -> "오늘 10분 몸 움직이기"
            Regex("공부|시험|과제|책|독서").containsMatchIn(text) -> "오늘 집중해서 20분 공부하기"
            Regex("휴식|쉬|피곤|힘들|여유|긴장|걱정").containsMatchIn(text) -> "잠깐 멈춰 깊게 숨 세 번 고르기"
            Regex("감사|고마|행복").containsMatchIn(text) -> "오늘 고마웠던 순간 하나 남기기"
            else -> text.lineSequence().first { it.isNotBlank() }.trim().take(45)
        }
        return saveGoal(listOf(goal), 1)
    }

    fun demoFlowers(month: YearMonth): List<Diary> = listOf(
        Mood.CALM, Mood.TIRED, Mood.WORRY, Mood.CALM, Mood.JOY, Mood.EXCITED,
        Mood.WORRY, Mood.CALM, Mood.JOY, Mood.WORRY
    ).mapIndexed { index, mood ->
        Diary("demo-flower-$index", month.atDay(index + 1), LocalTime.of(18, 30),
            "", "시연 예시", false, mood, mood)
    }

    fun tasks(default: String) = prefs.getString("tasks", null)?.split("\n") ?: listOf(default)
    fun duration() = prefs.getInt("goal_days", 7)
    fun goalEnd(): LocalDate? = prefs.getString("goal_end", null)?.let(LocalDate::parse)
    fun saveGoal(tasks: List<String>, days: Int) = prefs.edit().putString("tasks", tasks.joinToString("\n"))
        .putInt("goal_days", days).putString("goal_end", LocalDate.now().plusDays(days.toLong() - 1).toString())
        .remove("checked_${LocalDate.now()}").commit()
    fun checked() = prefs.getStringSet("checked_${LocalDate.now()}", emptySet()).orEmpty().toSet()
    fun toggle(task: String): Boolean {
        val previous = checked()
        val updated = if (task in previous) previous - task else previous + task
        return prefs.edit().putStringSet("checked_${LocalDate.now()}", updated).commit()
    }
    fun clear() = prefs.edit().clear().commit()
}
