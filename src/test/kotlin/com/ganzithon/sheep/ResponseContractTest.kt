package com.ganzithon.sheep

import com.ganzithon.sheep.bootstrap.BootstrapResponse
import com.ganzithon.sheep.bootstrap.RequiredConsents
import com.ganzithon.sheep.card.*
import com.ganzithon.sheep.common.*
import com.ganzithon.sheep.feed.*
import com.ganzithon.sheep.fortune.FortuneResponse
import com.ganzithon.sheep.morning.*
import com.ganzithon.sheep.night.WritingOfferResponse
import com.ganzithon.sheep.session.FinishResponse
import com.ganzithon.sheep.session.NightSummary
import com.ganzithon.sheep.sheep.*
import com.ganzithon.sheep.user.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.LocalDate

@SpringBootTest
class ResponseContractTest {
    @Autowired lateinit var mapper: JsonMapper
    private val at = Instant.parse("2026-11-10T17:40:00Z")
    private val date = LocalDate.of(2026, 11, 10)
    private val card = CardResponse("c1", true, CardKind.TEXT, CardTone.WARM, CategoryId.CAREER,
        AuthorType.SEED, "먼저 지나간 양", TextCardContent("오늘도 괜찮아"), CardViewer(false, false, false))
    private val summary = NightSummary(14, FinishedStatus.STOPPED, at, 1, 2, 1, card)

    @Test
    fun `bootstrap contains v06 stats named screen and last session`() {
        val body = json(BootstrapResponse(at, "Asia/Seoul", Phase.NIGHT, date, NextScreen.NIGHT_CLOSING,
            UserResponse("u1", "user@example.com", null, 1, StatsResponse(66, 658, 3)), "n1", null, summary,
            null, ReminderResponse(false, null), RequiredConsents("1", "1")))
        assertThat(body.path("next_screen").asString()).isEqualTo("NIGHT_CLOSING")
        assertThat(body.path("phase").asString()).isEqualTo("night")
        assertThat(body.path("server_time").asString()).isEqualTo("2026-11-10T17:40:00Z")
        assertThat(body.path("user").path("stats").path("counted_sheep_total").asLong()).isEqualTo(658)
        assertThat(body.path("last_session").path("closing_card").path("id").asString()).isEqualTo("c1")
        assertThat(body.has("support")).isTrue()
        assertThat(body.path("support").isNull).isTrue()
    }

    @Test
    fun `finish includes the full night summary`() {
        val body = json(FinishResponse("s1", FinishedStatus.STOPPED, FinishReason.STOP_PAUSE, 14,
            card, WritingOfferResponse(WritingState.OFFERED), summary))
        assertThat(body.path("reason").asString()).isEqualTo("stop_pause")
        assertThat(body.path("summary").path("entrusted_count").asInt()).isEqualTo(1)
        assertThat(body.path("summary").path("fluff_count").asInt()).isEqualTo(2)
        assertThat(body.path("summary").path("yarn_sent_count").asInt()).isEqualTo(1)
    }

    @Test
    fun `morning carries saved time entrust flag totals and candidate positions`() {
        val body = json(MorningResponse(MorningNight("n1", date, "고민", at, true, CategoryId.CAREER), 14,
            listOf(CandidateResponse(card, true, false, null, 16, at)), "c1", null, null,
            emptyList(), WritingOfferResponse(WritingState.DEFERRED)))
        assertThat(body.path("counted_sheep").asInt()).isEqualTo(14)
        assertThat(body.path("night").path("worry_saved_at").asString()).isEqualTo("2026-11-10T17:40:00Z")
        assertThat(body.path("night").path("entrusted").asBoolean()).isTrue()
        val candidate = body.path("candidate_cards").get(0)
        assertThat(candidate.has("position")).isTrue()
        assertThat(candidate.path("position").isNull).isTrue()
        assertThat(candidate.path("sheep_count").asInt()).isEqualTo(16)
    }

    @Test
    fun `empty morning preserves nullable fields and empty arrays`() {
        val body = json(MorningResponse(null, null, emptyList(), null, null, null, emptyList(), null))
        for (field in listOf("night", "counted_sheep", "default_card_id", "adoption", "reflection", "writing_offer")) {
            assertThat(body.has(field)).isTrue()
            assertThat(body.path(field).isNull).isTrue()
        }
        assertThat(body.path("candidate_cards").isArray).isTrue()
        assertThat(body.path("saved_songs").isEmpty).isTrue()
    }

    @Test
    fun `sheep details retain candidate context and fortune exposes zodiac`() {
        val sheep = SheepResponse("sh1", "n1", date, "c1", null, at, emptyList(), 1)
        val body = json(SheepDetailResponse(sheep, card, 7, 16, SheepNight(date, null, CategoryId.CAREER, null)))
        assertThat(body.path("position").asInt()).isEqualTo(7)
        assertThat(body.path("sheep_count").asInt()).isEqualTo(16)
        assertThat(body.path("sheep").path("color_key").isNull).isTrue()
        val fortune = json(FortuneResponse(date, Zodiac.GEMINI, null, SheepType.RELAXED, "메시지", "보라", "책", "산책"))
        assertThat(fortune.path("zodiac").asString()).isEqualTo("gemini")
        assertThat(fortune.path("sheep_type").asString()).isEqualTo("relaxed")
    }

    @Test
    fun `feed item shapes and list envelopes are distinct`() {
        val body = json(FeedResponse("s1", 1, 2, listOf(CardFeedItem("i1", 1, card),
            PauseFeedItem("i2", 2, PauseResponse(PauseKind.ENTRUST_WORRY, null)))))
        assertThat(body.path("items").get(0).has("pause")).isFalse()
        assertThat(body.path("items").get(1).has("card")).isFalse()
        assertThat(body.path("items").get(1).path("pause").path("action").isNull).isTrue()
        assertThat(json(ItemsResponse(emptyList<String>())).has("next_cursor")).isFalse()
        assertThat(json(PageResponse(emptyList<String>(), null)).path("next_cursor").isNull).isTrue()
    }

    private fun json(value: Any) = mapper.readTree(mapper.writeValueAsString(value))
}
