package kr.co.sai.diary

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
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
        fun scores(text: String): Map<Mood, Int> = entries.associateWith { mood ->
            mood.words.sumOf { word -> Regex(Regex.escape(word)).findAll(text).count() }
        }.filterValues { it > 0 }
    }
}

internal data class Diary(
    val id: String, val date: LocalDate, val time: LocalTime, val body: String,
    val moment: String, val voice: Boolean, val flower: Mood?
) {
    val summary get() = body.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(70)
    val mood get() = flower ?: Mood.scores(body).maxByOrNull { it.value }?.key
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
                Mood.entries.find { it.name == entry.optString("flower") })
        }.sortedWith(compareByDescending<Diary> { it.date }.thenByDescending { it.time })
    }

    private fun save(entries: List<Diary>): Boolean {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(JSONObject().put("id", entry.id).put("date", entry.date.toString())
                .put("time", entry.time.toString()).put("body", entry.body)
                .put("moment", entry.moment).put("voice", entry.voice)
                .put("flower", entry.flower?.name))
        }
        return prefs.edit().putString("entries", array.toString()).commit()
    }

    fun add(body: String, moment: String, voice: Boolean): Diary? {
        val entry = Diary(UUID.randomUUID().toString(), LocalDate.now(), LocalTime.now().withNano(0),
            body.trim(), moment, voice, null)
        return entry.takeIf { save(entries() + entry) }
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
