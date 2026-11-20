package com.yangsenunbam.night

import com.yangsenunbam.common.*
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.text.Normalizer
import java.util.Locale

/** Limited Korean/English demo rules, not a clinical assessment or a safety guarantee.
 * No external transmission, input logging, or automatic emergency action.
 * A non-match only means these rules did not identify an expression requiring guidance.
 */
@Component
@ConditionalOnProperty(prefix = "app.safety", name = ["mode"], havingValue = "local", matchIfMissing = true)
class LocalWorrySafetyGateway : WorrySafetyGateway {
    override fun check(worry: String): WorryVerdict {
        if (worry.isBlank() || worry.codePointCount(0, worry.length) > 500) {
            throw ApiException(ErrorCode.SAFETY_CHECK_UNAVAILABLE, "고민 내용을 확인하지 못했어요.")
        }
        val normalized = Normalizer.normalize(worry, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT).replace(Regex("[\\p{Cf}]"), "")
        // Negation is scoped to exact phrases, never to the whole paragraph. A second
        // affirmative clause still triggers guidance. Quoted disclosures are not stripped.
        val clauses = normalized.split(Regex("[.!?。！？\\n;]+|하지만|그런데|그래도|\\bbut\\b"))
        val needsSupport = clauses.any { clause ->
            var compact = clause.replace(Regex("\\s+"), "")
            negations.forEach { compact = compact.replace(it, " ") }
            if (quotedExercise.matches(compact)) false
            else korean.any { it.containsMatchIn(compact) } || english.any { it.containsMatchIn(clause) }
        }
        return if (needsSupport) WorryVerdict(true, guidance) else WorryVerdict(false)
    }

    companion object {
        // Keep rules reviewable and tested here. Do not add blanket word exclusions:
        // words such as "시험", "영화", or "아니다" can coexist with a real disclosure.
        private val negations = listOf(
            "죽고싶지않", "죽고싶은건아니", "죽고싶은것은아니", "자살할생각은없", "자살할생각이없",
            "자해할생각은없", "자해할생각이없", "자해하고싶지않", "살고싶지않은건아니",
        )
        private val quotedExercise = Regex("""^(?:수업에서)?[‘'"“]죽고싶다[’'"”]라는(?:문장|표현)을(?:분석|번역)하는과제가어려워$""")
        private val korean = listOf(
            "죽고싶", "죽어버리고싶", "살고싶지않", "살기싫", "살아있고싶지않",
            "(?:내|제)?목숨을?(?:끊|끝내)", "(?:내|제)?삶을?끝내고싶",
            "자살(?:하고싶|할거|할꺼|하려|하겠|할생각|할계획|을생각|을계획)",
            "자해(?:하고싶|할거|할꺼|하려|하겠|할생각|할계획|했|했어|하고있)",
            "(?:나자신|내몸|스스로)[을를]?해치고싶",
        ).map(::Regex)
        private val english = listOf(
            "\\bi\\s+(?:want|plan)\\s+to\\s+(?:die|kill\\s+myself|hurt\\s+myself)\\b",
            "\\bi(?:'m| am)\\s+(?:going\\s+to\\s+kill\\s+myself|suicidal)\\b",
            "\\bi\\s+(?:do not|don't)\\s+want\\s+to\\s+live\\b",
        ).map(::Regex)
        private val guidance = SupportResponse(
            "지금 혼자 감당하기 어려운 마음이라면 잠시 양 세기를 멈추고, 믿을 수 있는 사람에게 연락해 함께 있어 달라고 요청해 보세요. 이 안내는 진단이 아니며 앱은 긴급 구조를 요청하지 않습니다.",
            listOf(SupportResource("믿을 수 있는 사람에게 연락하기", SupportAction.OPEN_CONTACTS, null)),
        )
    }
}
