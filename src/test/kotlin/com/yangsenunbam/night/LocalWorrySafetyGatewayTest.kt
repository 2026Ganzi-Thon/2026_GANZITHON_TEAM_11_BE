package com.yangsenunbam.night

import com.yangsenunbam.common.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class LocalWorrySafetyGatewayTest {
    private val gateway = LocalWorrySafetyGateway()

    @ParameterizedTest
    @ValueSource(strings = ["시험이 걱정돼", "취업이 안 돼서 힘들어", "피곤해 죽겠다", "자살 예방 교육 과제가 있어",
        "죽고 싶지 않아. 살고 싶어", "자살할 생각은 없어", "자해하고 싶지 않아", "죽고 싶은 건 아니야",
        "수업에서 ‘죽고 싶다’라는 문장을 분석하는 과제가 어려워", "I don't want to die", "I want to live"])
    fun `ordinary concerns and scoped negative statements do not trigger`(text: String) {
        assertEquals(WorryVerdict(false), gateway.check(text))
    }

    @ParameterizedTest
    @ValueSource(strings = ["죽고 싶어", "죽 고 싶 어", "죽\u200B고 싶어", "살고 싶지 않아", "내 목숨을 끊고 싶어",
        "자살하려고 해", "자해하고 싶어", "나 자신을 해치고 싶어", "자해했어",
        "죽고 싶지 않아. 하지만 자해하고 싶어", "친구에게 ‘죽고 싶어’라고 말했어",
        "자살 예방 교육을 들었지만 나는 죽고 싶어", "영화를 봤어. 지금 죽고 싶어",
        "I WANT TO DIE", "I'm suicidal", "I don't want to live"])
    fun `direct disclosures retain supportive guidance`(text: String) {
        val result = gateway.check(text)
        assertTrue(result.needsSupport)
        assertFalse(result.support!!.message.contains(text))
        assertEquals(SupportAction.OPEN_CONTACTS, result.support.resources.single().action)
        assertNull(result.support.resources.single().target)
    }

    @Test fun `invalid inputs do not become a normal decision`() {
        for (text in listOf("", " ", "a".repeat(501))) {
            assertEquals(ErrorCode.SAFETY_CHECK_UNAVAILABLE,
                assertThrows(ApiException::class.java) { gateway.check(text) }.code)
        }
    }

    private val context = ApplicationContextRunner()
        .withUserConfiguration(LocalWorrySafetyGateway::class.java, HttpWorrySafetyGateway::class.java)

    @Test fun `default selects local implementation without remote credentials`() {
        context.run { assertInstanceOf(LocalWorrySafetyGateway::class.java, it.getBean(WorrySafetyGateway::class.java)) }
    }

    @Test fun `http mode never silently falls back to local rules`() {
        context.withPropertyValues("app.safety.mode=http").run {
            val checker = it.getBean(WorrySafetyGateway::class.java)
            assertInstanceOf(HttpWorrySafetyGateway::class.java, checker)
            assertEquals(ErrorCode.SAFETY_CHECK_UNAVAILABLE,
                assertThrows(ApiException::class.java) { checker.check("fixture") }.code)
        }
    }
}
