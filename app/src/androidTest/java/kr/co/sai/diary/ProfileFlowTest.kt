package kr.co.sai.diary

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime

@RunWith(AndroidJUnit4::class)
class ProfileFlowTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @Before fun reset() { ProfileStore(context).clear(); JournalStore(context).clear() }
    @After fun clean() { ProfileStore(context).clear(); JournalStore(context).clear() }
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun click(activity: MainActivity, text: String) {
        descendants(activity.window.decorView).filterIsInstance<Button>().single { it.text.toString() == text }.performClick()
    }
    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        // Wait for SurfaceFlinger to present the first frame after launch/navigation.
        android.os.SystemClock.sleep(1000)
        instrumentation.waitForIdleSync()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        assertNotNull(bitmap)
        val directory = java.io.File(context.externalCacheDir, "screenshots").apply { mkdirs() }
        val file = java.io.File(directory, "$name.png")
        file.outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        // Gradle uninstalls the test target; retain screenshots outside its app directory.
        listOf("mkdir -p /sdcard/Download/sai-screenshots",
            "cp ${file.absolutePath} /sdcard/Download/sai-screenshots/$name.png").forEach { command ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(command)
            ).use { it.readBytes() }
        }
    }
    @Test fun captureFirstRow() {
        ActivityScenario.launch(MainActivity::class.java).use { capture("01-profile") }
        ProfileStore(context).save(Profile())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            capture("02-seed")
            scenario.onActivity { click(it, "씨앗 심기") }
            capture("03-day-home")
        }
    }
    private fun input(activity: MainActivity, name: String): EditText =
        descendants(activity.window.decorView).filterIsInstance<EditText>().single { it.contentDescription == name }

    @Test fun profileDraftSurvivesRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                input(activity, "호칭").setText("정원사 김")
                input(activity, "나이").setText("25")
                input(activity, "성별 (선택)").setText("직접 입력")
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertEquals("정원사 김", input(activity, "호칭").text.toString())
                assertEquals("25", input(activity, "나이").text.toString())
                assertEquals("직접 입력", input(activity, "성별 (선택)").text.toString())
            }
        }
    }

    @Test fun invalidAgeStaysOnProfileAndGenderCanBeBlank() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                input(activity, "호칭").setText("정원사")
                input(activity, "나이").setText("131")
                click(activity, "다음")
                assertNotNull(input(activity, "나이").error)
                assertTrue(input(activity, "성별 (선택)").text.isEmpty())
                assertFalse(ProfileStore(context).hasProfile())
            }
        }
    }

    @Test fun dataPersistsAndDeletesCompletely() {
        val store = ProfileStore(context)
        val expected = Profile("운전자", "출발할 때", "30대", "선택 안 함", "고마운 순간 떠올리기")
        assertTrue(store.save(expected)); assertTrue(store.plant()); assertTrue(store.setWeather("밤 비"))
        val reopened = ProfileStore(context)
        assertEquals(expected, reopened.load()); assertTrue(reopened.hasProfile()); assertTrue(reopened.plantedToday())
        assertEquals("밤 비", reopened.weather())
        assertTrue(reopened.clear()); assertFalse(reopened.hasProfile()); assertFalse(reopened.plantedToday())
        assertEquals(Profile(), reopened.load()); assertEquals("낮 맑음", reopened.weather())
    }
    @Test fun demoStartsInDayAndBloomsUsingSavedEmotion() {
        ProfileStore(context).save(Profile())
        val db = JournalStore(context)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                click(activity, "씨앗 심기")
                assertEquals("낮 맑음", ProfileStore(context).weather())
                assertEquals("시연 예시", db.entries().single().moment)
                assertTrue(db.seedYesterday())
                assertEquals(1, db.entries().size)
                assertTrue(db.saveSpokenGoal("오늘은 퇴근 후에 산책하고 싶어요."))
                assertEquals(listOf("오늘 10분 몸 움직이기"), db.tasks(""))
                val entry = checkNotNull(db.add("오늘 기쁘고 즐거운 일이 있었어요.", "출발", true))
                assertEquals(Mood.JOY, entry.emotion)
                assertNull(entry.flower)
                click(activity, "밤으로 전환")
                assertEquals("밤 맑음", ProfileStore(context).weather())
                assertEquals(Mood.JOY, db.entries().first().flower)
                assertTrue(descendants(activity.window.decorView).filterIsInstance<TextView>()
                    .any { it.text.toString() == "꽃말   새로운 시작" })
                descendants(activity.window.decorView).filterIsInstance<Button>()
                    .single { it.contentDescription == "기록" }.performClick()
                click(activity, "어제 일기")
                assertTrue(descendants(activity.window.decorView).filterIsInstance<TextView>()
                    .any { it.text.toString().contains("퇴근길에 노을") })
            }
            scenario.recreate()
            assertEquals("밤 맑음", ProfileStore(context).weather())
            assertEquals(2, db.entries().size)
        }
    }
    @Test fun voiceEntryKeepsRecordingDateAndAppearsInRecordsAfterRestart() {
        ProfileStore(context).apply { save(Profile()); plant(); setWeather("낮 맑음") }
        val recordedAt = LocalDateTime.now().withHour(23).withMinute(59).withSecond(0).withNano(0)
        val diary = checkNotNull(JournalStore(context).add(
            "오늘은 편안했어요.", "출발", voice = true, recordedAt = recordedAt
        ))
        assertEquals(recordedAt.toLocalDate(), diary.date)
        assertEquals(recordedAt.toLocalTime(), diary.time)
        assertNotNull(JournalStore(context).add(diary.body, diary.moment, true, recordedAt, diary.id))
        assertEquals(listOf(diary), JournalStore(context).entries())
        ActivityScenario.launch(MainActivity::class.java).use { }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                val records = descendants(a.window.decorView).filterIsInstance<Button>()
                    .single { it.contentDescription == "기록" }
                records.performClick()
                assertTrue(descendants(a.window.decorView).filterIsInstance<TextView>()
                    .any { it.text.toString() == "1편" })
                fun clickDescription(text: String) = descendants(a.window.decorView).filterIsInstance<Button>()
                    .single { it.contentDescription == text }.performClick()
                clickDescription("${diary.date.monthValue}월 일기장")
                repeat((diary.date.dayOfMonth - 1) / 6) { click(a, "다음 날짜 ›") }
                clickDescription("${diary.date.monthValue}월 ${diary.date.dayOfMonth}일 일기 보기")
                click(a, "음성 기록")
                assertTrue(descendants(a.window.decorView).filterIsInstance<TextView>()
                    .any { it.text.toString() == diary.body })
            }
        }
    }

    @Test fun yearPickerConfirmsBeforeChangingAndOpensEmptyState() {
        ProfileStore(context).apply { save(Profile()); plant(); setWeather("낮 맑음") }
        val currentYear = LocalDateTime.now().year
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                descendants(activity.window.decorView).filterIsInstance<Button>()
                    .single { it.contentDescription == "기록" }.performClick()
                click(activity, "$currentYear ﹀")
                click(activity, (currentYear - 1).toString())
                click(activity, "뒤로")
                click(activity, "$currentYear ﹀")
                click(activity, (currentYear - 1).toString())
                click(activity, "확인")
                assertTrue(descendants(activity.window.decorView).filterIsInstance<TextView>()
                    .any { it.text.toString() == "기록 없음" })
                click(activity, "연도 선택")
                click(activity, currentYear.toString())
                click(activity, "확인")
                click(activity, "날짜별")
            }
        }
    }

}
