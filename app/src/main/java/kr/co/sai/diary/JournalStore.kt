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
    val emotion: Mood = Mood.classify(body),
    val tags: List<String> = suggestDiaryTags(body)
) {
    val isDemo get() = id.startsWith("demo-") && moment.startsWith("시연 예시")
    val summary get() = body.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(70)
    val mood get() = flower ?: emotion
}


internal fun normalizeDiaryTags(text: String): List<String> = text.split(Regex("[,，#\\s]+"))
    .map { it.trim().take(12) }.filter { it.isNotEmpty() }.distinct().take(6)

internal fun suggestDiaryTags(text: String): List<String> = listOf(
    "출근" to "출근|출발", "업무" to "업무|작업|회의|발표", "동료" to "동료",
    "점심" to "점심|식사", "퇴근" to "퇴근|집에", "노을" to "노을|주황빛",
    "음악" to "음악|노래", "휴식" to "쉼|쉬|숨을 고르|여유"
).filter { (_, pattern) -> Regex(pattern).containsMatchIn(text) }.map { it.first }.take(6)

internal fun summarizeDay(records: List<Diary>): String {
    val sentenceBreak = Regex("(?<=[.!?。！？])\\s+|[\\r\\n]+")
    val whitespace = Regex("\\s+")
    val seen = mutableSetOf<String>()
    return records.sortedBy { it.time }.mapNotNull { entry ->
        val sentences = entry.body.trim().split(sentenceBreak)
            .map { it.trim().replace(whitespace, " ") }.filter { it.isNotEmpty() }
        val emotionSentence = sentences.indexOfFirst { Mood.scores(it).isNotEmpty() }
        val excerpt = sentences.filterIndexed { index, _ ->
            index == 0 || index == emotionSentence
        }.filter { seen.add(it) }.joinToString(" ") { sentence ->
            if (sentence.length > 140) sentence.take(139).trimEnd() + "…" else sentence
        }
        excerpt.takeIf { it.isNotEmpty() }
    }.joinToString("\n\n")
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
                    ?: Mood.classify(entry.getString("body")),
                entry.optJSONArray("tags")?.let { tags ->
                    normalizeDiaryTags((0 until tags.length()).joinToString(",") { tags.getString(it) })
                } ?: suggestDiaryTags(entry.getString("body")))
        }.sortedWith(compareByDescending<Diary> { it.date }.thenByDescending { it.time })
    }

    private fun save(entries: List<Diary>): Boolean {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(JSONObject().put("id", entry.id).put("date", entry.date.toString())
                .put("time", entry.time.toString()).put("body", entry.body)
                .put("moment", entry.moment).put("voice", entry.voice)
                .put("flower", entry.flower?.name).put("emotion", entry.emotion.name)
                .put("tags", JSONArray(entry.tags)))
        }
        return prefs.edit().putString("entries", array.toString()).commit()
    }

    fun add(
        body: String, moment: String, voice: Boolean,
        recordedAt: LocalDateTime = LocalDateTime.now(),
        id: String = UUID.randomUUID().toString(),
        tags: List<String> = suggestDiaryTags(body)
    ): Diary? {
        val entry = Diary(id, recordedAt.toLocalDate(), recordedAt.toLocalTime(), body.trim(), moment, voice, null,
            tags = normalizeDiaryTags(tags.joinToString(",")))
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

    // Keep the sample date stable so the November 13 demo can be revisited later.
    fun seedYesterday(today: LocalDate = LocalDate.now()): Boolean {
        if (prefs.getBoolean("demo_day_v2_seeded", false)) return true
        val current = entries()
        val previous = current.firstOrNull { it.id == "demo-yesterday" }
        // A removed sample must stay removed on upgrade.
        if (previous == null && prefs.getBoolean("demo_yesterday_seeded", false)) {
            return prefs.edit().putBoolean("demo_day_v2_seeded", true).commit()
        }
        val date = previous?.date ?: LocalDate.of(2026, 11, 13)
        val samples = demoDayEntries(date)
        val legacyBodies = setOf(
            "퇴근길에 노을을 보니 마음이 편안해졌어요. 좋아하는 음악을 들으며 천천히 집에 왔어요.",
            "퇴근길에 노을을 보니 마음이 편안해졌어요. 바쁘게 흘러간 하루 끝에 좋아하는 음악을 들으며 천천히 집에 오는 시간이 작은 쉼이 되었어요.\n\n아침에는 처리할 일이 많아 서둘러 출근했어요. 오전에 막혔던 일을 동료와 함께 정리하고, 점심에는 따뜻한 밥을 먹으며 잠깐 일상 이야기를 나눴어요. 오후에는 미뤄두었던 작업을 하나 마무리해서 한결 홀가분했어요.\n\n신호를 기다리며 창밖을 보니 하늘이 주황빛으로 물들어 있었어요. 늘 지나던 길인데 오늘은 가로수와 거리의 불빛이 유난히 눈에 들어왔어요. 차 안에서 잠시 숨을 고르니 복잡했던 생각도 차분해졌어요.\n\n집에 도착하면 따뜻한 물로 씻고 저녁을 챙겨 먹으려고 해요. 오늘 할 일을 모두 끝내지는 못했지만, 애쓴 나에게 이 정도면 잘했다고 말해주고 싶어요. 내일 아침에는 조금 일찍 나와 여유 있게 하루를 시작해봐야겠어요."
        )
        val keepPrevious = previous != null &&
            (previous.body !in legacyBodies || previous.moment != "시연 예시" || previous.voice || previous.flower != null)
        val additions = samples.filter { sample ->
            current.none { it.id == sample.id } || (sample.id == "demo-yesterday" && !keepPrevious)
        }
        val next = current.filterNot { old -> additions.any { it.id == old.id } } + additions
        if (!save(next)) return false
        return prefs.edit().putBoolean("demo_yesterday_seeded", true)
            .putBoolean("demo_day_v2_seeded", true).commit()
    }

    fun upgradeExistingDemo(): Boolean =
        !prefs.getBoolean("demo_yesterday_seeded", false) || seedYesterday()

    private fun demoDayEntries(date: LocalDate): List<Diary> = listOf(
        Diary("demo-nov13-morning", date, LocalTime.of(8, 30),
            "아침에는 처리할 일이 많아 평소보다 서둘러 출근했어요. 오전 회의에서 발표할 생각에 조금 긴장했어요. " +
                "차 안에서 오늘 할 일을 차례대로 정리하고 깊게 숨을 쉬었어요. 한 번에 다 해내기보다 하나씩 마무리해보려고 해요.",
            "시연 예시 · 출근", true, null, tags = listOf("출근", "업무", "발표", "마음정리")),
        Diary("demo-nov13-noon", date, LocalTime.of(12, 40),
            "오전에 막혔던 작업을 동료와 함께 정리해서 마음이 한결 편안해졌어요. 점심에는 따뜻한 밥을 먹으며 " +
                "주말 계획 이야기를 나눴어요. 잠깐 웃고 나니 어깨에 들어갔던 힘도 풀렸어요. " +
                "혼자 끙끙대기보다 도움을 구해도 괜찮다는 걸 다시 느꼈어요.",
            "시연 예시 · 점심", false, null, tags = listOf("업무", "동료", "점심", "감사")),
        Diary("demo-yesterday", date, LocalTime.of(18, 40),
            "퇴근길에 노을을 보니 마음이 편안해졌어요. 바쁘게 흘러간 하루 끝에 좋아하는 음악을 들으며 천천히 집에 오는 시간이 작은 쉼이 되었어요.\n\n아침에는 처리할 일이 많아 서둘러 출근했어요. 오전에 막혔던 일을 동료와 함께 정리하고, 점심에는 따뜻한 밥을 먹으며 잠깐 일상 이야기를 나눴어요. 오후에는 미뤄두었던 작업을 하나 마무리해서 한결 홀가분했어요.\n\n신호를 기다리며 창밖을 보니 하늘이 주황빛으로 물들어 있었어요. 늘 지나던 길인데 오늘은 가로수와 거리의 불빛이 유난히 눈에 들어왔어요. 차 안에서 잠시 숨을 고르니 복잡했던 생각도 차분해졌어요.\n\n집에 도착하면 따뜻한 물로 씻고 저녁을 챙겨 먹으려고 해요. 오늘 할 일을 모두 끝내지는 못했지만, 애쓴 나에게 이 정도면 잘했다고 말해주고 싶어요. 내일 아침에는 조금 일찍 나와 여유 있게 하루를 시작해봐야겠어요.",
            "시연 예시 · 퇴근", true, Mood.CALM,
            tags = listOf("퇴근", "노을", "음악", "휴식", "내일의다짐"))
    )

    fun bloomToday(today: LocalDate = LocalDate.now(), preferredId: String? = null): Boolean {
        val records = entries().filter { it.date == today && !it.isDemo }
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
