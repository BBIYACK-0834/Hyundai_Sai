package kr.co.sai.diary

import android.content.Context

data class Profile(
    val nickname: String = "친구",
    val moment: String = "하루의 시작과 끝",
    val age: String = "선택 안 함",
    val gender: String = "선택 안 함",
    val goal: String = "하루 끝에 일기 말하기"
)

/** Local-only profile. No account, network or analytics. */
class ProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("sai_profile", Context.MODE_PRIVATE)
    fun load() = Profile(
        prefs.getString("nickname", "친구")!!,
        prefs.getString("moment", "하루의 시작과 끝")!!,
        prefs.getString("age", "선택 안 함")!!,
        prefs.getString("gender", "선택 안 함")!!,
        prefs.getString("goal", "하루 끝에 일기 말하기")!!
    )
    fun save(profile: Profile): Boolean = prefs.edit()
        .putString("nickname", profile.nickname).putString("moment", profile.moment)
        .putString("age", profile.age).putString("gender", profile.gender)
        .putString("goal", profile.goal).putBoolean("onboarded", true).commit()
    fun hasProfile() = prefs.getBoolean("onboarded", false)
    fun plantedToday() = prefs.getString("plantedDate", null) == java.time.LocalDate.now().toString()
    fun plant() = prefs.edit().putString("plantedDate", java.time.LocalDate.now().toString()).commit()
    fun weather() = prefs.getString("weather", "낮 맑음")!!
    fun setWeather(value: String) = prefs.edit().putString("weather", value).commit()
    fun clear() = prefs.edit().clear().commit()
}
