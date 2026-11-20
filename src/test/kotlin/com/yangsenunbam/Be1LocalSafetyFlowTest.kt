package com.yangsenunbam

import com.yangsenunbam.auth.*
import com.yangsenunbam.common.*
import com.yangsenunbam.night.*
import com.yangsenunbam.security.CurrentUser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.convention.TestBean
import java.time.*
import java.util.UUID

@SpringBootTest(properties = ["app.safety.mode=local"])
class Be1LocalSafetyFlowTest {
    @Autowired lateinit var auth: AuthService
    @Autowired lateinit var users: AuthStore
    @Autowired lateinit var nights: NightService
    @Autowired lateinit var store: NightStore
    @Autowired lateinit var safety: WorrySafetyGateway
    @TestBean(methodName = "fixedClock") lateinit var clock: Clock

    private fun account(): CurrentUser {
        val email = "safety-${UUID.randomUUID()}@example.test"
        auth.register(RegisterRequest(email, "FixturePass123!"), UUID.randomUUID().toString())
        return CurrentUser(users.findUserByEmail(email)!!.id, true)
    }
    @Test fun `ordinary worry persists with no external service configured`() {
        assertInstanceOf(LocalWorrySafetyGateway::class.java, safety)
        val user = account()
        val result = nights.recordWorry(user, WorryRequest(CategoryId.CAREER, "취업이 걱정돼"))
        assertEquals(NightStatus.READY, result.status)
        assertEquals("취업이 걱정돼", store.owned(user.id, result.id).worryText)
    }
    @Test fun `support disclosure persists and explicit continue unlocks night`() {
        val user = account()
        val request = WorryRequest(CategoryId.OTHER, "죽고 싶어")
        val result = nights.recordWorry(user, request)
        assertEquals(NightStatus.NEEDS_SUPPORT, result.status)
        assertNotNull(result.support)
        assertEquals(result.id, nights.recordWorry(user, request).id)
        assertEquals(NightStatus.READY, nights.continueAfterSupport(user, result.id).status)
        assertNotNull(store.owned(user.id, result.id).supportContinuedAtMs)
    }
    companion object {
        @JvmStatic fun fixedClock(): Clock = Clock.fixed(Instant.parse("2026-11-16T13:00:00Z"), ZoneOffset.UTC)
    }
}
