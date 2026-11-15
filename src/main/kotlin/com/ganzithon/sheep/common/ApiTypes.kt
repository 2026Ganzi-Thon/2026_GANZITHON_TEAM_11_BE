package com.ganzithon.sheep.common

import com.fasterxml.jackson.annotation.JsonValue

data class ItemsResponse<T>(val items: List<T>)
data class PageResponse<T>(val items: List<T>, val nextCursor: String?)

interface WireValue {
    @get:JsonValue
    val value: String
}

enum class CategoryId(override val value: String) : WireValue {
    ROMANCE("romance"), CAREER("career"), STUDY("study"), HEALTH("health"),
    RELATIONSHIPS("relationships"), FAMILY("family"), OTHER("other"), NONE("none")
}
enum class Zodiac(override val value: String) : WireValue {
    ARIES("aries"), TAURUS("taurus"), GEMINI("gemini"), CANCER("cancer"), LEO("leo"), VIRGO("virgo"),
    LIBRA("libra"), SCORPIO("scorpio"), SAGITTARIUS("sagittarius"), CAPRICORN("capricorn"), AQUARIUS("aquarius"), PISCES("pisces")
}
enum class Phase(override val value: String) : WireValue { DAY("day"), NIGHT("night") }
enum class LegalDocumentType(override val value: String) : WireValue { TERMS("terms"), PRIVACY("privacy") }
enum class NightStatus(override val value: String) : WireValue { READY("ready"), NEEDS_SUPPORT("needs_support"), CLOSED("closed") }
enum class SessionStatus(override val value: String) : WireValue {
    ACTIVE("active"), COMPLETED("completed"), STOPPED("stopped"), EXPIRED("expired")
}
enum class FinishedStatus(override val value: String) : WireValue { COMPLETED("completed"), STOPPED("stopped") }
enum class FinishReason(override val value: String) : WireValue { COMPLETED("completed"), LEAVE("leave"), STOP_PAUSE("stop_pause") }
enum class PauseKind(override val value: String) : WireValue { ENTRUST_WORRY("entrust_worry"), STOP_OFFER("stop_offer") }
enum class PauseAction(override val value: String) : WireValue { ENTRUSTED("entrusted"), SKIPPED("skipped") }
enum class WritingChoice(override val value: String) : WireValue { NOW("now"), MORNING("morning") }
enum class WritingState(override val value: String) : WireValue {
    OFFERED("offered"), WRITING("writing"), DEFERRED("deferred"), FULFILLED("fulfilled")
}
enum class CardKind(override val value: String) : WireValue { TEXT("text"), SONG("song") }
enum class CardTone(override val value: String) : WireValue { EMPATHY("empathy"), LIGHT("light"), WARM("warm") }
enum class AuthorType(override val value: String) : WireValue {
    USER("user"), SEED("seed"), EDITORIAL("editorial"), MONGCHI("mongchi"), AI("ai")
}
enum class ModerationStatus(override val value: String) : WireValue {
    PENDING("pending"), APPROVED("approved"), REJECTED("rejected")
}
enum class ModerationDecision(override val value: String) : WireValue { APPROVED("approved"), REJECTED("rejected") }
enum class ReflectionAnswer(override val value: String) : WireValue {
    SAME("same"), SMALLER("smaller"), MUCH_BETTER("much_better"), BIGGER("bigger"),
    NEW_WORRY("new_worry"), CANNOT_RECALL("cannot_recall")
}
enum class SheepType(override val value: String) : WireValue {
    SPARKLING("sparkling"), FAVORABLE("favorable"), RELAXED("relaxed"), RESTING("resting")
}
enum class SupportAction(override val value: String) : WireValue {
    CALL("call"), OPEN_CONTACTS("open_contacts"), OPEN_URL("open_url")
}
enum class NextScreen {
    ONBOARDING_CONSENT, ONBOARDING_BIRTH_DATE, DAY_INTRO, MORNING_ADOPTION, HOME,
    NIGHT_SUPPORT, NIGHT_FEED, NIGHT_CLOSING, NIGHT_RETURN, NIGHT_SHEEP_COUNT, NIGHT_WORRY
}

data class SupportResponse(val message: String, val resources: List<SupportResource>)
data class SupportResource(val label: String, val action: SupportAction, val target: String?)
