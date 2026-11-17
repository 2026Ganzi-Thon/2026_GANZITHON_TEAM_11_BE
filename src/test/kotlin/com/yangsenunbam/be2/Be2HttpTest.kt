package com.yangsenunbam.be2

import com.yangsenunbam.auth
import com.yangsenunbam.common.*
import com.yangsenunbam.feed.port.NightStore
import com.yangsenunbam.fortune.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import java.time.Clock

@SpringBootTest
@AutoConfigureMockMvc
@Import(Be2HttpTest.Adapters::class)
class Be2HttpTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var f: Be2Fixture
    @Autowired lateinit var fortunes: MemoryFortunePort

    @BeforeEach
    fun reset() {
        val fresh = Be2Fixture()
        f.store.nights = fresh.store.nights
        f.store.sessions = fresh.store.sessions
        f.store.cards = fresh.store.cards
        f.store.cardsAtClose = fresh.store.cardsAtClose
        f.store.receipts.clear()
        f.store.onBeforeCommit = {}
        f.clock.current = fresh.clock.current
        fortunes.saved.clear()
        fortunes.daily = null
        fortunes.fallback = null
        fortunes.userZodiac = Zodiac.GEMINI
    }

    @Test
    fun `all eleven BE2 endpoints execute services and serialize the specification contract`() {
        mvc.perform(get("/api/v1/sessions/s1/feed").with(auth())).andExpect(status().isOk)
            .andExpect(jsonPath("$.total_count").value(7))
            .andExpect(jsonPath("$.items[3].pause.kind").value("entrust_worry"))
        mvc.perform(post("/api/v1/sessions/s1/resume").header("Idempotency-Key", "resume").with(auth()))
            .andExpect(status().isOk).andExpect(jsonPath("$.writer_epoch").value(2))
        putJson("/sessions/s1/progress", """{"position":4,"furthest_position":4,"sequence":1,"writer_epoch":2}""")
            .andExpect(status().isOk).andExpect(jsonPath("$.furthest_position").value(4))
        putJson("/sessions/s1/pauses/s1-i4", """{"action":"entrusted"}""")
            .andExpect(status().isOk).andExpect(jsonPath("$.item_id").value("s1-i4"))
        putJson("/sessions/s1/progress", """{"position":5,"furthest_position":5,"sequence":2,"writer_epoch":2}""").andExpect(status().isOk)
        putJson("/nights/n1/fluff/s1-c1", """{"saved":true}""").andExpect(status().isOk).andExpect(jsonPath("$.saved").value(true))
        putJson("/nights/n1/songs/s1-c5", """{"saved":true}""").andExpect(status().isOk)
        // BE1의 finish가 저장하는 상태를 준비한다. BE1 엔드포인트는 이 테스트의 범위가 아니다.
        val session = f.store.sessions.getValue("s1")
        f.store.sessions["s1"] = session.copy(state = session.state.copy(status = SessionStatus.STOPPED), closingCardId = "s1-c7")
        putJson("/sessions/s1/closing-view", """{"viewed":true}""").andExpect(status().isNoContent).andExpect(content().string(""))
        f.day()
        mvc.perform(get("/api/v1/morning").with(auth())).andExpect(status().isOk)
            .andExpect(jsonPath("$.counted_sheep").value(5))
            .andExpect(jsonPath("$.night.entrusted").value(true))
            .andExpect(jsonPath("$.default_card_id").value("s1-c7"))
            .andExpect(jsonPath("$.saved_songs[0].track.id").value("track"))
            .andExpect(jsonPath("$.writing_offer.state").value("deferred"))
        putJson("/nights/n1/adoption", """{"card_id":null}""").andExpect(status().isOk)
            .andExpect(jsonPath("$.card_id").value("s1-c7")).andExpect(jsonPath("$.placements").isArray)
        putJson("/nights/n1/reflection", """{"answer":"smaller"}""").andExpect(status().isOk)
            .andExpect(jsonPath("$.message").value("봐, 밤엔 커 보였던 거야."))
        fortunes.daily = FortuneResponse(f.time.fortuneDate(), Zodiac.GEMINI, null, SheepType.RELAXED, "문구", "보라", "책", "산책")
        mvc.perform(get("/api/v1/fortune/today").with(auth())).andExpect(status().isOk)
            .andExpect(jsonPath("$.zodiac").value("gemini")).andExpect(jsonPath("$.date").value("2026-11-11"))
            .andExpect(jsonPath("$.rank").value(null as Any?))
    }

    @Test
    fun `BE2 errors keep status code request id and retry header`() {
        putJson("/sessions/s1/progress", """{"position":1,"furthest_position":1,"sequence":1,"writer_epoch":2}""")
            .andExpect(status().isConflict).andExpect(jsonPath("$.error.code").value("WRITER_REPLACED"))
            .andExpect(jsonPath("$.error.request_id").isString)
        mvc.perform(get("/api/v1/fortune/today").with(auth())).andExpect(status().isServiceUnavailable)
            .andExpect(header().string("Retry-After", "1")).andExpect(jsonPath("$.error.code").value("FORTUNE_UNAVAILABLE"))
        mvc.perform(get("/api/v1/sessions/missing/feed").with(auth())).andExpect(status().isNotFound)
        mvc.perform(get("/api/v1/morning")).andExpect(status().isUnauthorized)
        mvc.perform(get("/api/v1/morning").with(auth(complete = false))).andExpect(status().isForbidden)
    }

    private fun putJson(path: String, body: String) = mvc.perform(put("/api/v1$path").with(auth())
        .contentType(MediaType.APPLICATION_JSON).content(body))

    @TestConfiguration(proxyBeanMethods = false)
    class Adapters {
        @Bean fun fixture() = Be2Fixture()
        @Bean @Primary fun nightStore(fixture: Be2Fixture): NightStore = fixture.store
        @Bean @Primary fun testClock(fixture: Be2Fixture): Clock = fixture.clock
        @Bean @Primary fun fortunes() = MemoryFortunePort()
    }
}
