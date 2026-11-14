package kr.co.sai.diary

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Button
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
            capture("03-weather")
        }
    }
    @Test fun selectedProfileSurvivesRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { a -> click(a, "정원사"); click(a, "도착했을 때"); click(a, "30대"); click(a, "한 번 깊게 숨 고르기") }
            scenario.recreate()
            scenario.onActivity { a ->
                val selected = descendants(a.window.decorView).filterIsInstance<Button>().filter { it.isSelected }.map { it.text.toString() }
                assertTrue(selected.containsAll(listOf("정원사", "도착했을 때", "30대", "한 번 깊게 숨 고르기")))
            }
        }
    }
    @Test fun ageCanBeClearedAndGenderIsOptional() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                click(a, "20대"); click(a, "20대")
                val selected = descendants(a.window.decorView).filterIsInstance<Button>().filter { it.isSelected }.map { it.text.toString() }
                assertFalse(selected.contains("20대")); assertTrue(selected.contains("선택 안 함"))
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
    @Test fun seedMovesToWeatherAndRestoresSelection() {
        ProfileStore(context).save(Profile())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { a -> click(a, "씨앗 심기") }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertTrue(ProfileStore(context).plantedToday())
            scenario.onActivity { a ->
                val rain = descendants(a.window.decorView).filterIsInstance<Button>().filter { it.text.toString() == "비" }
                assertEquals(2, rain.size); rain[1].performClick()
            }
            scenario.recreate()
            scenario.onActivity { a ->
                val labels = descendants(a.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }
                assertEquals("밤 비", ProfileStore(context).weather())
                assertTrue("오늘은 어떤 하루였나요?" in labels)
                assertTrue("오늘의 꽃" in labels)
            }
        }
    }
    @Test fun journalEntryAppearsInBooksAndCalendar() {
        ProfileStore(context).apply { save(Profile()); plant(); setWeather("낮 맑음") }
        val diary = checkNotNull(JournalStore(context).add("오늘은 편안했어요.", "출발", voice = false))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                val records = descendants(a.window.decorView).filterIsInstance<Button>()
                    .single { it.contentDescription == "기록" }
                records.performClick()
                click(a, "날짜별")
                val day = descendants(a.window.decorView).filterIsInstance<Button>()
                    .single { it.contentDescription == "${diary.date.monthValue}월 ${diary.date.dayOfMonth}일 일기 보기" }
                day.performClick()
                assertTrue(descendants(a.window.decorView).filterIsInstance<TextView>().any { it.text.toString() == diary.body })
            }
        }
    }
}
