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
    @Test fun recapCombinesEarnedAndSampleFlowersWithoutAddingSamplesToGarden() {
        ProfileStore(context).apply { save(Profile()); plant() }
        val db = JournalStore(context)
        val now = LocalDateTime.now()
        val entry = checkNotNull(db.add("오늘은 너무 슬펐어요.", "도착", false, now))
        assertTrue(db.bloomToday(now.toLocalDate(), entry.id))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                descendants(activity.window.decorView).filterIsInstance<Button>()
                    .single { it.contentDescription == "마이페이지" }.performClick()
                click(activity, "리캡")
                fun shows(text: String) = descendants(activity.window.decorView).filterIsInstance<TextView>()
                    .any { it.text.toString() == text }
                assertTrue(shows("내 꽃 1송이 · 시연 18송이"))
                assertTrue(shows("3송이")) // Two sample flowers and one earned flower for sadness.
                click(activity, "내 정원 보기")
                assertTrue(shows("1송이"))
            }
        }
    }

    @Test fun gardenShowsEarnedFlowersAcrossMonthsAndYears() {
        ProfileStore(context).apply { save(Profile()); plant() }
        val db = JournalStore(context)
        assertTrue(db.seedYesterday())
        val earlier = LocalDateTime.of(2025, 12, 1, 18, 0)
        val later = LocalDateTime.of(2026, 1, 1, 18, 0)
        for (date in listOf(earlier, later)) {
            val entry = checkNotNull(db.add("오늘은 정말 기뻤어요.", "도착", false, date))
            assertTrue(db.bloomToday(date.toLocalDate(), entry.id))
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                descendants(activity.window.decorView).filterIsInstance<Button>()
                    .single { it.contentDescription == "마이페이지" }.performClick()
                click(activity, "정원 도감")
                val views = descendants(activity.window.decorView)
                assertTrue(views.filterIsInstance<TextView>().any { it.text.toString() == "2송이" })
                val blooms = views.filterIsInstance<android.widget.ImageView>()
                    .filter { it.contentDescription?.startsWith("프리지아 · ") == true }
                assertEquals(2, blooms.size)
                assertFalse(views.filterIsInstance<Button>().any { it.text.matches(Regex("[0-9]+월")) })
                blooms.first().performClick()
                assertTrue(descendants(activity.window.decorView).filterIsInstance<TextView>()
                    .any { it.text.toString().contains("전체 피어난 횟수 · 2회") })
            }
        }
    }

    @Test fun demoStartsInDayAndBloomsUsingSavedEmotion() {
        ProfileStore(context).save(Profile())
        val db = JournalStore(context)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                click(activity, "씨앗 심기")
                assertEquals("낮 맑음", ProfileStore(context).weather())
                assertTrue(db.entries().all { it.isDemo })
                assertTrue(db.seedYesterday())
                assertEquals(3, db.entries().size)
                assertTrue(db.saveSpokenGoal("오늘은 퇴근 후에 산책하고 싶어요."))
                assertEquals(listOf("오늘은 퇴근 후에 산책하고 싶어요."), db.tasks(""))
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
                click(activity, "시연 일기")
                assertTrue(descendants(activity.window.decorView).filterIsInstance<TextView>()
                    .any { it.text.toString().contains("퇴근길에 노을") })
            }
            scenario.recreate()
            assertEquals("밤 맑음", ProfileStore(context).weather())
            assertEquals(4, db.entries().size)
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


    @Test fun daySummaryUsesAllTimesAndTimePickerOpensFullEntry() {
        ProfileStore(context).apply { save(Profile()); plant(); setWeather("낮 맑음") }
        val db = JournalStore(context)
        val now = LocalDateTime.now()
        val evening = checkNotNull(db.add("저녁에 산책했어요. 노을을 보니 행복했어요. 집 앞에서 신발을 털었어요.",
            "도착", true, now.withHour(18).withMinute(40)))
        val morning = checkNotNull(db.add("아침에 발표했어요. 시작할 때는 긴장했어요. 커피도 샀어요.",
            "출발", true, now.withHour(8).withMinute(30)))
        val summary = "아침에 발표했어요. 시작할 때는 긴장했어요. 저녁에 산책했어요. 노을을 보니 행복했어요."
        assertEquals(summary, summarizeDay(listOf(evening, morning)))

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                descendants(activity.window.decorView).filterIsInstance<Button>()
                    .single { it.contentDescription == "기록" }.performClick()
                click(activity, "오늘 일기")
                fun shows(text: String) = descendants(activity.window.decorView).filterIsInstance<TextView>()
                    .any { it.text.toString() == text }
                assertTrue(shows(summary))
                click(activity, "시간별 기록")
                assertTrue(shows("시간대 선택"))
                click(activity, "08:30 · 걱정")
                assertTrue(shows(morning.body))
                click(activity, "18:40 · 기쁨")
                assertTrue(shows(evening.body))
                click(activity, "요약 일기")
                assertTrue(shows(summary))
            }
        }
    }


    @Test fun sampleUpgradePreservesEditedDiaryAndDoesNotDuplicateOrRestoreDeletedSamples() {
        val db = JournalStore(context)
        val date = java.time.LocalDate.of(2026, 11, 13)
        val edited = checkNotNull(db.add("내가 직접 수정한 예시 본문", "시연 예시", false,
            date.atTime(18, 40), "demo-yesterday", tags = listOf("내기록")))
        context.getSharedPreferences("sai_journal", Context.MODE_PRIVATE).edit()
            .putBoolean("demo_yesterday_seeded", true).commit()
        assertTrue(db.upgradeExistingDemo())
        assertEquals(edited, db.entries().single { it.id == edited.id })
        assertEquals(3, db.entries().size)
        assertTrue(db.seedYesterday())
        assertEquals(3, db.entries().size)
        assertTrue(db.delete("demo-nov13-morning"))
        assertTrue(db.upgradeExistingDemo())
        assertEquals(2, db.entries().size)
        assertTrue(db.clear())
        context.getSharedPreferences("sai_journal", Context.MODE_PRIVATE).edit()
            .putBoolean("demo_yesterday_seeded", true).commit()
        assertTrue(db.upgradeExistingDemo())
        assertTrue(db.entries().isEmpty())
    }

    @Test fun oldDiaryLoadsWithoutTagsAndExplicitEmptyTagsPersist() {
        val prefs = context.getSharedPreferences("sai_journal", Context.MODE_PRIVATE)
        prefs.edit().putString("entries", """[{"id":"legacy","date":"2026-11-13",
            "time":"18:40","body":"퇴근길 노을을 보았어요","moment":"도착","voice":false}]""").commit()
        val db = JournalStore(context)
        val old = db.entries().single()
        assertEquals(listOf("퇴근", "노을"), old.tags)
        assertTrue(db.replace(old.copy(tags = emptyList())))
        assertTrue(JournalStore(context).entries().single().tags.isEmpty())
        assertEquals(listOf("퇴근", "노을", "휴식"), normalizeDiaryTags("#퇴근, 노을 #퇴근 휴식"))
    }

    @Test fun sampleDayShowsThreeTimesTwoVoiceExamplesAndEmotionTags() {
        ProfileStore(context).apply { save(Profile()); plant(); setWeather("낮 맑음") }
        val db = JournalStore(context)
        assertTrue(db.seedYesterday())
        val samples = db.entries().filter { it.isDemo }
        assertEquals(3, samples.size)
        assertTrue(samples.all { it.date == java.time.LocalDate.of(2026, 11, 13) })
        assertEquals(2, samples.count { it.voice })
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                descendants(a.window.decorView).filterIsInstance<Button>()
                    .single { it.contentDescription == "기록" }.performClick()
                // Three recordings on November 13 are one daily diary.
                assertTrue(descendants(a.window.decorView).filterIsInstance<TextView>()
                    .any { it.text.toString() == "1편" })
                assertFalse(descendants(a.window.decorView).filterIsInstance<TextView>()
                    .any { it.text.toString() == "3편" })
                click(a, "시연 일기")
                fun shows(text: String) = descendants(a.window.decorView).filterIsInstance<TextView>()
                    .any { it.text.toString().contains(text) }
                fun hasDailyFlower() = descendants(a.window.decorView).filterIsInstance<android.widget.ImageView>()
                    .any { it.contentDescription == "하루 대표 꽃: 술패랭이" }
                assertTrue(shows("3개의 기록"))
                assertTrue(hasDailyFlower())
                click(a, "시간별 기록")
                click(a, "08:30 · 걱정")
                assertTrue(shows("오전 회의"))
                assertTrue(hasDailyFlower())
                assertFalse(shows("근거 키워드"))
                assertFalse(shows("일치 없음"))
                assertTrue(descendants(a.window.decorView).filterIsInstance<TextView>()
                    .any { it.contentDescription == "감정 태그: 걱정" })
                assertFalse(shows("#출근"))
                click(a, "12:40 · 평온")
                assertTrue(shows("주말 계획"))
                assertTrue(hasDailyFlower())
                click(a, "음성 기록")
                assertFalse(descendants(a.window.decorView).filterIsInstance<Button>()
                    .any { it.text.toString().startsWith("12:40") })
                click(a, "18:40 · 평온")
                assertTrue(shows("실제 녹음 없음"))
                assertTrue(hasDailyFlower())
                assertTrue(shows("퇴근길에 노을"))
                assertTrue(descendants(a.window.decorView).filterIsInstance<TextView>()
                    .any { it.contentDescription == "감정 태그: 평온" })
            }
        }
    }
    @Test fun captureDemoDetailTabs() {
        ProfileStore(context).apply { save(Profile()); plant(); setWeather("낮 맑음") }
        assertTrue(JournalStore(context).seedYesterday())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                descendants(a.window.decorView).filterIsInstance<Button>()
                    .single { it.contentDescription == "기록" }.performClick()
                click(a, "시연 일기")
            }
            capture("04-demo-summary")
            scenario.onActivity { click(it, "시간별 기록"); click(it, "12:40 · 평온") }
            capture("05-demo-times")
            scenario.onActivity { click(it, "음성 기록"); click(it, "18:40 · 평온") }
            capture("06-demo-voice")
        }
    }

    @Test fun firstMorningAnswerIsSavedExactlyAndSurvivesRestart() {
        val db = JournalStore(context)
        val date = java.time.LocalDate.now()
        assertFalse(db.hasMorningGoal(date))
        assertFalse(db.saveSpokenGoal("  ", date))
        assertFalse(db.hasMorningGoal(date))
        ProfileStore(context).apply { save(Profile()); plant(); setWeather("낮 맑음") }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(descendants(activity.window.decorView).filterIsInstance<TextView>()
                    .any { it.text.toString().contains("목표는 무엇인가요?") })
                assertTrue(descendants(activity.window.decorView).filterIsInstance<Button>()
                    .any { it.contentDescription == "목표 답하기" })
            }
        }
        val answer = "오늘은 퇴근 후 20분 산책하고 싶어요."
        assertTrue(db.saveSpokenGoal(answer, date))
        assertTrue(JournalStore(context).hasMorningGoal(date))
        assertEquals(listOf(answer), JournalStore(context).tasks(""))
        assertTrue(db.entries().isEmpty())
        assertFalse(db.hasMorningGoal(date.plusDays(1)))
        ProfileStore(context).apply { save(Profile()); plant(); setWeather("낮 맑음") }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(descendants(activity.window.decorView).filterIsInstance<Button>()
                    .any { it.contentDescription == "일기 녹음" })
            }
        }
    }

    @Test fun dailySummaryOmitsMetadataAndRepeatedSentences() {
        val db = JournalStore(context)
        val now = LocalDateTime.now()
        val first = checkNotNull(db.add("오늘은 마음이 편안했어요.", "출발", false, now.withHour(8).withMinute(30)))
        val last = checkNotNull(db.add(first.body, "도착", false, now.withHour(18).withMinute(40)))
        val summary = summarizeDay(listOf(last, first))
        assertFalse(Regex("\\d{2}:\\d{2}").containsMatchIn(summary))
        assertFalse(summary.contains("\n"))
        assertEquals("오늘은 마음이 편안했어요.", summary)
        assertEquals(Mood.JOY, Mood.classify("작업을 끝내서 뿌듯하고 고마웠어요."))
        assertEquals(Mood.WORRY, Mood.classify("내일 발표가 걱정되고 불안해요."))
        assertEquals(Mood.TIRED, Mood.classify("피곤하고 지쳤어요."))
        assertEquals(Mood.SAD, Mood.classify("외로워서 눈물이 났어요."))
        assertEquals(Mood.EXCITED, Mood.classify("내일 여행이 기대되고 설레요."))
        assertEquals(Mood.CALM, Mood.classify("특별한 표현이 없는 기록"))
    }

    @Test fun morningAnswerKeepsExistingGoalsChecksAndDuration() {
        val db = JournalStore(context)
        val existing = listOf("물 마시기", "숨 고르기", "일기 남기기")
        assertTrue(db.saveGoal(existing, 14))
        assertTrue(db.toggle(existing.first()))
        val end = db.goalEnd()
        val answer = "퇴근 후 20분 산책하기"
        assertTrue(db.saveSpokenGoal(answer))
        assertEquals(listOf(answer) + existing, db.tasks(""))
        assertEquals(setOf(existing.first()), db.checked())
        assertEquals(14, db.duration())
        assertEquals(end, db.goalEnd())
        assertTrue(db.saveSpokenGoal(answer))
        assertEquals(4, db.tasks("").size)
        assertTrue(db.saveSpokenGoal("오늘은 책 읽기"))
        assertEquals(listOf("오늘은 책 읽기") + existing, JournalStore(context).tasks(""))
        assertEquals(existing, db.tasks("", java.time.LocalDate.now().plusDays(1)))
    }

}
