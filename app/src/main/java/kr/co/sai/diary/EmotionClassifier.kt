package kr.co.sai.diary

/** Deterministic, on-device classification of diary text; no audio or text leaves the app. */
internal object EmotionClassifier {
    // Match common spoken conjugations rather than only dictionary forms.
    private val patterns = mapOf(
        Mood.CALM to "편안|편해|편했|평온|차분|고요|안심|후련|마음이 놓|마음이 놓였",
        Mood.WORRY to "걱정|불안|긴장|부담|막막|초조|두렵|두려|무섭|무서|겁나|겁이 나|떨려|떨렸",
        Mood.TIRED to "피곤|지쳤|지쳐|지치|지침|힘들|힘든|힘드|졸려|졸리|졸렸|녹초|지친|고단|버겁|버거|기진맥진|기운이 없",
        Mood.JOY to "행복|기쁘|기뻐|기뻤|기쁜|기쁨|즐거|즐겁|즐겼|좋았|좋아졌|기분이 좋|기분 좋|뿌듯|홀가분|감사|고마|신나|신났|신난|신이 나|웃었|웃음이 나",
        Mood.EXCITED to "설레|설렜|설렘|설렌|기대돼|기대되|기대됐|기대가 돼|기대가 되|두근|기대하고",
        Mood.SAD to "슬프|슬퍼|슬펐|슬픈|슬픔|서럽|서러|서운|속상|우울|울었|울고|울컥|눈물|외롭|외로|허전|공허|쓸쓸|상처받|상처 받"
    ).mapValues { Regex(it.value) }
    private val clauses = Regex("[.!?。！？,\\n]+|하지만|그런데|그치만|그래도|지만|이제는|지금은")
    private val prefixNegation = Regex("(?:안|못)\\s*$")
    private val suffixNegation = Regex("^[가-힣]{0,4}\\s*(?:지\\s*(?:않|않았|못)|진\\s*않|하지\\s*않|하지는\\s*않|하지도\\s*않|하지\\s*못|않|아니|없)")
    private val intensifier = Regex("(?:너무|정말|진짜|매우|아주|엄청|무척|많이)\\s*$")

    fun scores(text: String): Map<Mood, Int> {
        val result = linkedMapOf<Mood, Int>()
        val parts = text.trim().split(clauses).filter { it.isNotBlank() }
        parts.forEachIndexed { index, raw ->
            val clause = raw.trim().replace(Regex("\\s+"), " ")
            patterns.forEach { (mood, pattern) ->
                pattern.findAll(clause).forEach { match ->
                    val before = clause.substring(0, match.range.first)
                    val after = clause.substring(match.range.last + 1)
                    // Do not infer the opposite emotion from a denied feeling.
                    if (!prefixNegation.containsMatchIn(before) && !suffixNegation.containsMatchIn(after)) {
                        val weight = 2 + (if (intensifier.containsMatchIn(before)) 2 else 0) +
                            (if (index == parts.lastIndex) 1 else 0)
                        result[mood] = (result[mood] ?: 0) + weight
                    }
                }
            }
        }
        return result
    }

    fun classify(text: String): Mood = scores(text).maxByOrNull { it.value }?.key ?: Mood.CALM
}
