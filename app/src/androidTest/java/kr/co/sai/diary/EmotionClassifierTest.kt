package kr.co.sai.diary

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EmotionClassifierTest {
    @Test fun spokenKoreanAndNegations() {
        val cases = mapOf(
            "나 너무 슬퍼" to Mood.SAD,
            "오늘 정말 슬펐어요" to Mood.SAD,
            "너무 외로워" to Mood.SAD,
            "마음이 너무 우울해" to Mood.SAD,
            "친구 때문에 속상해요" to Mood.SAD,
            "눈물이 나" to Mood.SAD,
            "정말 기뻐" to Mood.JOY,
            "즐겁게 하루를 보냈어" to Mood.JOY,
            "오늘 신났어" to Mood.JOY,
            "기분이 좋아" to Mood.JOY,
            "정말 고마워" to Mood.JOY,
            "많이 지쳐" to Mood.TIRED,
            "오늘 너무 힘들어" to Mood.TIRED,
            "너무 졸려" to Mood.TIRED,
            "아침부터 피곤했어요" to Mood.TIRED,
            "내일이 두려워" to Mood.WORRY,
            "너무 무서워" to Mood.WORRY,
            "발표가 걱정돼" to Mood.WORRY,
            "조금 불안해" to Mood.WORRY,
            "내일 여행이 기대돼" to Mood.EXCITED,
            "너무 설레" to Mood.EXCITED,
            "마음이 편해" to Mood.CALM,
            "슬프지 않아" to Mood.CALM,
            "안 슬퍼" to Mood.CALM,
            "걱정 없어. 마음이 편안해" to Mood.CALM,
            "행복하지 않아. 너무 슬퍼" to Mood.SAD,
            "기쁘지 않아. 속상해" to Mood.SAD,
            "아침에는 슬펐지만 지금은 행복해" to Mood.JOY,
            "기뻤지만 지금은 슬퍼" to Mood.SAD,
            "기대출 금액을 확인했어요" to Mood.CALM,
            "좋아하는 음악을 틀었는데 너무 슬퍼" to Mood.SAD,
            "" to Mood.CALM
        )
        cases.forEach { (text, expected) -> assertEquals(text, expected, Mood.classify(text)) }
    }

    @Test fun oldIncorrectEmotionIsRecomputedAndEditsStayConsistent() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("sai_journal", Context.MODE_PRIVATE)
        val previous = prefs.getString("entries", null)
        try {
            val old = JSONObject().put("id", "emotion-regression")
                .put("date", "2026-11-15").put("time", "09:36:00")
                .put("body", "나 너무 슬퍼").put("moment", "일기")
                .put("voice", true).put("flower", "CALM").put("emotion", "CALM")
            assertTrue(prefs.edit().putString("entries", JSONArray().put(old).toString()).commit())
            val store = JournalStore(context)
            val entry = store.entries().single()
            assertEquals(Mood.SAD, entry.emotion)
            assertEquals(Mood.CALM, entry.flower) // Preserve a separately chosen flower.
            assertEquals("나 너무 슬퍼", entry.body)
            assertTrue(store.replace(entry.copy(body = "지금은 정말 행복해")))
            val edited = store.entries().single()
            assertEquals(Mood.JOY, edited.emotion)
            assertEquals(Mood.CALM, edited.flower)
        } finally {
            if (previous == null) prefs.edit().remove("entries").commit()
            else prefs.edit().putString("entries", previous).commit()
        }
    }
}
