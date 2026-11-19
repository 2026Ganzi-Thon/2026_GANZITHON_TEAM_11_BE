package com.yangsenunbam.be2

import com.yangsenunbam.auth
import com.yangsenunbam.common.*
import com.yangsenunbam.feed.*
import com.yangsenunbam.feed.model.*
import com.yangsenunbam.feed.persistence.*
import com.yangsenunbam.fortune.*
import com.yangsenunbam.fortune.persistence.MysqlFortunePort
import com.yangsenunbam.morning.*
import com.yangsenunbam.session.*
import com.yangsenunbam.night.NightStore as Be1NightStore
import com.yangsenunbam.night.NightRecord as Be1NightRecord
import java.util.UUID
import com.yangsenunbam.security.CurrentUser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.transaction.PlatformTransactionManager
import org.testcontainers.mysql.MySQLContainer
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Tag("mysql")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Import(MysqlBe2Test.TestClock::class)
class MysqlBe2Test {
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var store: MysqlNightStore
    @Autowired lateinit var cards: MysqlCardCatalog
    @Autowired lateinit var be1Sessions: SessionService
    @Autowired lateinit var be1SessionStore: SessionStore
    @Autowired lateinit var be1Nights: Be1NightStore
    @Autowired lateinit var feed: FeedService
    @Autowired lateinit var morning: MorningService
    @Autowired lateinit var fortune: FortuneService
    @Autowired lateinit var fortunePort: MysqlFortunePort
    @Autowired lateinit var clock: MutableClock
    @Autowired lateinit var mapper: JsonMapper
    @Autowired lateinit var manager: PlatformTransactionManager
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var closingJob: MysqlNightClosingJob
    private val user = CurrentUser("user-1", true)
    private val close = Instant.parse("2026-11-10T19:00:00Z")

    @BeforeEach
    fun seed() {
        // The test task targets a disposable *_test schema. BE1 tests use their own account IDs.
        listOf("feed_resume_receipts", "adopted_sheep", "night_reactions", "feed_card_views", "feed_items", "feed_session_progress",
            "night_feed_state", "feed_card_yarn", "feed_card_history", "feed_card_catalog", "fortune_results",
            "fortune_approved_daily", "fortune_approved_templates").forEach { jdbc.update("DELETE FROM $it") }
        jdbc.update("DELETE FROM be1_session_replays WHERE user_id IN ('user-1', 'user-2')")
        jdbc.update("DELETE FROM be1_sessions WHERE user_id IN ('user-1', 'user-2')")
        jdbc.update("DELETE FROM be1_nights WHERE user_id IN ('user-1', 'user-2')")
        jdbc.update("DELETE FROM user_onboarding WHERE user_id IN ('user-1', 'user-2')")
        jdbc.update("DELETE FROM auth_users WHERE id IN ('user-1', 'user-2')")
        jdbc.update("UPDATE feed_category_colors SET color_key = NULL WHERE category_id <> 'none'")
        for (id in listOf("user-1", "user-2")) {
            jdbc.update("INSERT INTO auth_users(id, email, password_hash, onboarding_complete, created_at_ms) VALUES (?, ?, ?, TRUE, ?)",
                id, "$id@example.test", "test-fixture-not-a-login-hash", 0)
            jdbc.update("INSERT INTO user_onboarding(user_id, birth_date) VALUES (?, ?)", id, java.sql.Date.valueOf("2000-06-01"))
        }
        val fixture = Be2Fixture()
        clock.current = fixture.clock.current
        fixture.store.cards.values.forEach { cards.publishApproved(it, "user-2", clock.current.minusSeconds(3600)) }
        val night = fixture.store.nights.getValue("n1")
        be1Nights.create(Be1NightRecord("n1", user.id, night.nightDate, night.categoryId, "내일 면접이 걱정돼요 🐑",
            night.worrySavedAt!!.toEpochMilli(), false, false, null, null, null))
        be1Nights.writingState("n1", WritingState.OFFERED)
        store.withUser(user.id) {
            val session = fixture.store.sessions.getValue("s1")
            be1SessionStore.create(user.id, "n1", 7, 1, session.state.startedAt.toEpochMilli(), close.toEpochMilli(), "s1")
            store.saveFeed("s1", session.items)
        }
    }

    @Test
    fun `MySQL roundtrip preserves view context original progress and Unicode`() {
        assertThat(feed.feed(user, "s1").items).hasSize(7)
        assertThat(count("feed_card_views")).isZero()
        progress(7)
        clock.current = clock.current.plusSeconds(10)
        progress(2, 2, 2)
        val freshStore = MysqlNightStore(jdbc, manager, mapper, cards)
        freshStore.withUser(user.id) { tx ->
            val session = tx.session("s1")!!
            assertThat(session.progress()).isEqualTo(ProgressResponse(2, 7, 2, 1))
            assertThat(session.lastProgress).isEqualTo(ProgressRequest(2, 2, 2, 1))
            assertThat(session.views.getValue("s1-c2").viewedAt).isEqualTo(clock.current)
            assertThat(tx.night("n1")!!.worryText).endsWith("🐑")
        }
        assertThat(progress(2, 2, 2).furthestPosition).isEqualTo(7)
        fails(ErrorCode.SEQUENCE_CONFLICT) { progress(2, 7, 2) }
    }

    @Test
    fun `concurrent resume writes one receipt and advances the epoch once`() {
        progress(3)
        val responses = concurrent { feed.resume(user, "s1", "same-key") }
        assertThat(responses.map { it.writerEpoch }.distinct()).containsExactly(2L)
        assertThat(count("feed_resume_receipts")).isEqualTo(1)
        fails(ErrorCode.WRITER_REPLACED) { progress(4, sequence = 9) }
        feed.resume(user, "s1", "different-key")
        clock.current = close
        assertThat(feed.resume(user, "s1", "same-key")).isEqualTo(responses.first())
        assertThat(jdbc.queryForObject("SELECT writer_epoch FROM be1_sessions WHERE id = 's1'", Long::class.java)).isEqualTo(3)
    }

    @Test
    fun `concurrent progress retains greatest sequence and maximum position`() {
        Executors.newFixedThreadPool(6).use { pool ->
            pool.invokeAll((1L..12L).map { seq -> Callable { progress((seq % 7 + 1).toInt(), 7, seq) } }).forEach { it.get(10, TimeUnit.SECONDS) }
        }
        store.withUser(user.id) {
            assertThat(it.session("s1")!!.progress()).isEqualTo(ProgressResponse(6, 7, 12, 1))
        }
    }

    @Test
    fun `commit time guard rolls back progress views and resume receipts`() {
        fails(ErrorCode.SESSION_CLOSED) {
            store.withUser(user.id) {
                feed.resume(user, "s1", "late")
                feed.saveProgress(user, "s1", ProgressRequest(3, 3, 1, 2))
                clock.current = close
            }
        }
        assertThat(count("feed_resume_receipts")).isZero()
        assertThat(count("feed_card_views")).isZero()
        store.withUser(user.id) { tx ->
            assertThat(tx.session("s1")!!.progress()).isEqualTo(ProgressResponse(0, 0, 0, 1))
            assertThat(tx.night("n1")!!.viewSequence).isZero()
        }
    }

    @Test
    fun `card and feed creation roll back when source is unavailable or an active session exists`() {
        fails(ErrorCode.ACTIVE_SESSION_EXISTS) { be1Sessions.create(user, "n1", CreateSessionRequest(7), "create-second") }
        assertThat(sessionCount()).isEqualTo(1)
        assertThat(count("feed_items")).isEqualTo(7)
        endSession()
        cards.remove("s1-c7", clock.current)
        fails(ErrorCode.FEED_BUILD_UNAVAILABLE) { be1Sessions.create(user, "n1", CreateSessionRequest(7), "create-second") }
        assertThat(sessionCount()).isEqualTo(1)
    }

    @Test
    fun `approved catalog supplies a persistent ordered feed and hides removed content`() {
        endSession()
        val state = be1Sessions.create(user, "n1", CreateSessionRequest(16), "create-second")
        val result = feed.feed(user, state.id)
        assertThat(result.items.map { it.position }).containsExactlyElementsOf((1..16).toList())
        assertThat(result.items.filterIsInstance<PauseFeedItem>().map { it.position }).containsExactly(6, 14)
        assertThat(result.items.filterIsInstance<CardFeedItem>().count { it.card.kind == CardKind.SONG }).isEqualTo(2)
        cards.remove("s1-c7", clock.current)
        assertThat((feed.feed(user, state.id).items.last() as CardFeedItem).card.content).isNull()
        assertThat(jdbc.queryForObject("SELECT content_json IS NULL FROM feed_card_catalog WHERE id = 's1-c7'", Boolean::class.java)).isTrue()
        assertThat(count("feed_card_history")).isEqualTo(7)
    }

    @Test
    fun `daytime cancellation persists and repeated song save preserves original timestamp`() {
        progress(5)
        feed.saveFluff(user, "n1", "s1-c1", SaveRequest(true))
        feed.saveSong(user, "n1", "s1-c5", SaveRequest(true))
        val original = jdbc.queryForObject("SELECT song_saved_at_ms FROM night_reactions WHERE card_id = 's1-c5'", Long::class.java)
        clock.current = clock.current.plusSeconds(5)
        feed.saveSong(user, "n1", "s1-c5", SaveRequest(true))
        assertThat(jdbc.queryForObject("SELECT song_saved_at_ms FROM night_reactions WHERE card_id = 's1-c5'", Long::class.java)).isEqualTo(original)
        clock.current = close
        assertThat(morning.morning(user, null).savedSongs).hasSize(1)
        feed.saveSong(user, "n1", "s1-c5", SaveRequest(false))
        assertThat(morning.morning(user, null).savedSongs).isEmpty()
        fails(ErrorCode.NIGHT_CLOSED) { feed.saveFluff(user, "n1", "s1-c1", SaveRequest(false)) }
    }

    @Test
    fun `late finalization retains the default at cutoff while deleted content stays hidden`() {
        progress(7)
        clock.current = close.plusSeconds(1)
        cards.remove("s1-c7", clock.current)
        val result = morning.morning(user, null)
        assertThat(result.defaultCardId).isEqualTo("s1-c7")
        assertThat(result.candidateCards.single { it.card.id == "s1-c7" }.canAdopt).isFalse()
        fails(ErrorCode.CARD_UNAVAILABLE) { morning.adopt(user, "n1", AdoptionRequest(null)) }
        assertThat(morning.morning(user, null).defaultCardId).isEqualTo("s1-c7")
        assertThat(jdbc.queryForObject("SELECT status FROM be1_sessions WHERE id = 's1'", String::class.java)).isEqualTo("expired")
    }

    @Test
    fun `concurrent adoption stores one sheep and preserves later BE3 sticker changes`() {
        progress(7)
        clock.current = close
        val sheep = concurrent { morning.adopt(user, "n1", AdoptionRequest(null)) }
        assertThat(sheep.map { it.id }.distinct()).hasSize(1)
        assertThat(count("adopted_sheep")).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT position FROM adopted_sheep", Int::class.java)).isEqualTo(7)
        val placements = """[{"instance_id":"p1","sticker_id":"st1","x":0.4,"y":0.6,"rotation":0,"scale":1}]"""
        jdbc.update("UPDATE adopted_sheep SET version = 2, placements_json = ?", placements)
        morning.reflect(user, "n1", ReflectionRequest(ReflectionAnswer.SMALLER))
        val retry = morning.adopt(user, "n1", AdoptionRequest(null))
        assertThat(retry.version).isEqualTo(2)
        assertThat(retry.placements.single().instanceId).isEqualTo("p1")
        fails(ErrorCode.ADOPTION_ALREADY_FINALIZED) { morning.adopt(user, "n1", AdoptionRequest("s1-c1")) }
    }

    @Test
    fun `different concurrent adoption choices have exactly one winning card`() {
        progress(7)
        clock.current = close
        val results = Executors.newFixedThreadPool(6).use { pool ->
            pool.invokeAll((1..12).map { index -> Callable {
                runCatching { morning.adopt(user, "n1", AdoptionRequest(if (index % 2 == 0) "s1-c1" else "s1-c7")) }
            } }).map { it.get(10, TimeUnit.SECONDS) }
        }
        val successes = results.mapNotNull { it.getOrNull() }
        assertThat(successes).isNotEmpty()
        assertThat(successes.map { it.cardId }.distinct()).hasSize(1)
        assertThat(results.mapNotNull { it.exceptionOrNull() }).allMatch {
            it is ApiException && it.code == ErrorCode.ADOPTION_ALREADY_FINALIZED
        }
        assertThat(count("adopted_sheep")).isEqualTo(1)
    }

    @Test
    fun `closing job catches up expired nights without creating a sheep`() {
        progress(7)
        clock.current = close.plusSeconds(120)
        closingJob.closeOverdue()
        closingJob.closeOverdue()
        store.withUser(user.id) { tx ->
            assertThat(tx.session("s1")!!.state.status).isEqualTo(SessionStatus.EXPIRED)
            assertThat(tx.night("n1")!!.defaultCardId).isEqualTo("s1-c7")
            assertThat(tx.night("n1")!!.writingState).isEqualTo(WritingState.DEFERRED)
        }
        assertThat(count("adopted_sheep")).isZero()
    }

    @Test
    fun `database unique key protects one sheep per night`() {
        progress(7)
        clock.current = close
        morning.adopt(user, "n1", AdoptionRequest(null))
        assertThrows<DataIntegrityViolationException> {
            jdbc.update("""INSERT INTO adopted_sheep(id, night_id, user_id, night_date, card_id, created_at_ms, sheep_count, placements_json)
                VALUES ('duplicate', 'n1', 'user-1', '2026-11-10', 's1-c1', 0, 7, '[]')""")
        }
    }

    @Test
    fun `other users cannot read write or adopt another nights data`() {
        val other = CurrentUser("user-2", true)
        fails(ErrorCode.RESOURCE_NOT_FOUND) { feed.feed(other, "s1") }
        fails(ErrorCode.RESOURCE_NOT_FOUND) { feed.saveProgress(other, "s1", ProgressRequest(1, 1, 1, 1)) }
        clock.current = close
        fails(ErrorCode.RESOURCE_NOT_FOUND) { morning.adopt(other, "n1", AdoptionRequest(null)) }
        fails(ErrorCode.RESOURCE_NOT_FOUND) { morning.reflect(other, "n1", ReflectionRequest(ReflectionAnswer.SMALLER)) }
        assertThat(morning.morning(other, null).night).isNull()
    }

    @Test
    fun `deleted worry stays deleted when BE2 writes reflection or reactions`() {
        progress(3)
        jdbc.update("UPDATE be1_nights SET worry_text = NULL, worry_deleted = TRUE WHERE id = 'n1'")
        feed.saveFluff(user, "n1", "s1-c1", SaveRequest(true))
        clock.current = close
        fails(ErrorCode.REFLECTION_NOT_AVAILABLE) { morning.reflect(user, "n1", ReflectionRequest(ReflectionAnswer.SMALLER)) }
        assertThat(morning.morning(user, null).night?.worryText).isNull()
    }

    @Test
    fun `fortune shares persisted approved fallback across users and changes date at 04`() {
        template()
        val results = concurrent { fortune.today(user) }
        assertThat(results.distinct()).hasSize(1)
        assertThat(fortune.today(CurrentUser("user-2", true))).isEqualTo(results.first())
        assertThat(count("fortune_results")).isEqualTo(1)
        jdbc.update("UPDATE fortune_approved_templates SET message = '바뀐 문구'")
        assertThat(fortune.today(user)).isEqualTo(results.first())
        clock.current = close
        assertThat(fortune.today(user).date.toString()).isEqualTo("2026-11-11")
        assertThat(fortune.today(user).message).isEqualTo("바뀐 문구")
        assertThat(count("fortune_results")).isEqualTo(2)
    }

    @Test
    fun `unavailable fortune is not cached and reviewed daily takes priority`() {
        fails(ErrorCode.FORTUNE_UNAVAILABLE) { fortune.today(user) }
        assertThat(count("fortune_results")).isZero()
        template()
        val daily = FortuneResponse(java.time.LocalDate.parse("2026-11-10"), Zodiac.GEMINI, 5, SheepType.RELAXED, "일일 문구", "보라", "책", "산책")
        jdbc.update("INSERT INTO fortune_approved_daily(fortune_date, zodiac, response_json) VALUES (?, ?, ?)", daily.date, daily.zodiac.value, mapper.writeValueAsString(daily))
        assertThat(fortune.today(user).message).isEqualTo("일일 문구")
        assertThat(fortune.today(user).rank).isNull()
        jdbc.update("UPDATE user_onboarding SET birth_date = '2000-05-20' WHERE user_id = 'user-1'")
        assertThat(fortunePort.zodiac(user.id)).isEqualTo(Zodiac.TAURUS)
    }

    @Test
    fun `eleven BE2 HTTP endpoints use MySQL with snake case responses`() {
        mvc.perform(get("/api/v1/sessions/s1/feed").with(auth())).andExpect(status().isOk)
        mvc.perform(post("/api/v1/sessions/s1/resume").with(auth()).header("Idempotency-Key", "http"))
            .andExpect(status().isOk).andExpect(jsonPath("$.writer_epoch").value(2))
        putJson("/sessions/s1/progress", """{"position":4,"furthest_position":4,"sequence":1,"writer_epoch":2}""").andExpect(status().isOk)
        putJson("/sessions/s1/pauses/s1-i4", """{"action":"entrusted"}""").andExpect(status().isOk)
        putJson("/sessions/s1/progress", """{"position":5,"furthest_position":5,"sequence":2,"writer_epoch":2}""").andExpect(status().isOk)
        putJson("/nights/n1/fluff/s1-c1", """{"saved":true}""").andExpect(status().isOk)
        putJson("/nights/n1/songs/s1-c5", """{"saved":true}""").andExpect(status().isOk)
        endSession()
        putJson("/sessions/s1/closing-view", """{"viewed":true}""").andExpect(status().isNoContent)
        clock.current = close
        mvc.perform(get("/api/v1/morning").with(auth())).andExpect(status().isOk)
            .andExpect(jsonPath("$.night.entrusted").value(true)).andExpect(jsonPath("$.default_card_id").value("s1-c7"))
        putJson("/nights/n1/adoption", """{"card_id":null}""").andExpect(status().isOk)
        putJson("/nights/n1/reflection", """{"answer":"smaller"}""").andExpect(status().isOk)
        template()
        mvc.perform(get("/api/v1/fortune/today").with(auth())).andExpect(status().isOk)
            .andExpect(jsonPath("$.zodiac").value("gemini"))
    }

    @Test
    fun `BE1 bearer flow continues through BE2 adoption to BE3 flock list and detail`() {
        clock.current = close.minusSeconds(60)
        val registration = mvc.perform(post("/api/v1/auth/register")
            .header("Idempotency-Key", "register-${UUID.randomUUID()}")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"email":"integration-${UUID.randomUUID()}@example.test","password":"FixturePass123!"}"""))
            .andExpect(status().isCreated).andReturn()
        val token = mapper.readTree(registration.response.contentAsString).path("access_token").asString()
        fun putWithToken(path: String, body: String) = mvc.perform(put("/api/v1$path")
            .header("Authorization", "Bearer $token").contentType(MediaType.APPLICATION_JSON).content(body))
        fun getWithToken(path: String) = mvc.perform(get("/api/v1$path").header("Authorization", "Bearer $token"))
        fun postWithToken(path: String, key: String, body: String? = null) = mvc.perform(post("/api/v1$path")
            .header("Authorization", "Bearer $token").header("Idempotency-Key", key).also {
                if (body != null) it.contentType(MediaType.APPLICATION_JSON).content(body)
            })

        putWithToken("/me/consents", """{"terms_version":"test-1","privacy_version":"test-1","terms_accepted":true,"privacy_accepted":true}""")
            .andExpect(status().isNoContent)
        putWithToken("/me/birth-date", """{"birth_date":"2000-06-01"}""").andExpect(status().isOk)
        putWithToken("/me/day-intro", """{"acknowledged":true}""").andExpect(status().isNoContent)
        val night = putWithToken("/nights/current/worry", """{"category_id":"none","worry_text":null}""")
            .andExpect(status().isOk).andReturn()
        val nightId = mapper.readTree(night.response.contentAsString).path("id").asString()
        val session = postWithToken("/nights/$nightId/sessions", "start", """{"sheep_count":3}""")
            .andExpect(status().isCreated).andReturn()
        val sessionId = mapper.readTree(session.response.contentAsString).path("id").asString()
        getWithToken("/sessions/$sessionId/feed").andExpect(status().isOk).andExpect(jsonPath("$.total_count").value(3))
        postWithToken("/sessions/$sessionId/resume", "resume").andExpect(status().isOk).andExpect(jsonPath("$.writer_epoch").value(2))
        putWithToken("/sessions/$sessionId/progress", """{"position":1,"furthest_position":1,"sequence":1,"writer_epoch":2}""")
            .andExpect(status().isOk)
        assertThat(jdbc.queryForObject("SELECT position FROM be1_sessions WHERE id = ?", Int::class.java, sessionId)).isEqualTo(1)
        postWithToken("/sessions/$sessionId/finish", "finish",
            """{"reason":"completed","position":3,"furthest_position":3,"sequence":2,"writer_epoch":2}""")
            .andExpect(status().isOk).andExpect(jsonPath("$.closing_card.id").value("s1-c7"))
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM be1_sessions WHERE night_id = ?", Int::class.java, nightId)).isEqualTo(1)

        clock.current = close
        getWithToken("/bootstrap").andExpect(status().isOk).andExpect(jsonPath("$.next_screen").value("MORNING_ADOPTION"))
        getWithToken("/morning").andExpect(status().isOk)
            .andExpect(jsonPath("$.counted_sheep").value(3)).andExpect(jsonPath("$.default_card_id").value("s1-c7"))
        val adoption = putWithToken("/nights/$nightId/adoption", """{"card_id":null}""")
            .andExpect(status().isOk).andExpect(jsonPath("$.card_id").value("s1-c7")).andReturn()
        val sheepId = mapper.readTree(adoption.response.contentAsString).path("id").asString()
        getWithToken("/sheep").andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].id").value(sheepId))
        getWithToken("/sheep/$sheepId").andExpect(status().isOk)
            .andExpect(jsonPath("$.sheep.night_id").value(nightId))
            .andExpect(jsonPath("$.card.id").value("s1-c7"))
            .andExpect(jsonPath("$.position").value(3))
            .andExpect(jsonPath("$.sheep_count").value(3))
    }

    private fun progress(position: Int, furthest: Int = position, sequence: Long = 1) = feed.saveProgress(user, "s1", ProgressRequest(position, furthest, sequence, 1))
    private fun count(table: String) = jdbc.queryForObject("SELECT COUNT(*) FROM $table", Int::class.java)!!
    private fun sessionCount() = jdbc.queryForObject("SELECT COUNT(*) FROM be1_sessions WHERE user_id = ?", Int::class.java, user.id)!!
    private fun endSession() {
        val state = be1SessionStore.owned(user.id, "s1")
        be1Sessions.finish(user, "s1", FinishRequest(FinishReason.LEAVE, maxOf(1, state.position),
            maxOf(1, state.furthestPosition), state.sequence + 1, state.writerEpoch), "finish-s1")
    }
    private fun template() { jdbc.update("""INSERT INTO fortune_approved_templates
        (zodiac, sheep_type, message, lucky_color, lucky_item, small_action) VALUES ('gemini', 'relaxed', '검수된 테스트 문구', '보라', '책', '산책')""") }
    private fun putJson(path: String, body: String) = mvc.perform(put("/api/v1$path").with(auth()).contentType(MediaType.APPLICATION_JSON).content(body))
    private fun <T> concurrent(action: () -> T): List<T> = Executors.newFixedThreadPool(6).use { pool ->
        pool.invokeAll((1..12).map { Callable { action() } }).map { it.get(10, TimeUnit.SECONDS) }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class TestClock {
        @Bean @Primary fun mysqlClock(): MutableClock = MutableClock()
    }

    companion object {
        private val externalUrl = System.getenv("MYSQL_TEST_URL")?.also {
            require(Regex("jdbc:mysql://(?:localhost|127\\.0\\.0\\.1):[0-9]+/[A-Za-z0-9_]+_test(?:\\?.*)?").matches(it)) {
                "MYSQL_TEST_URL must point to a disposable MySQL database named *_test"
            }
        }
        private val mysql by lazy {
            MySQLContainer("mysql:8.4").withDatabaseName("be2_test").withUsername("be2_test").withPassword("be2-test-only").also { it.start() }
        }
        @JvmStatic @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { externalUrl ?: mysql.jdbcUrl }
            registry.add("spring.datasource.username") { if (externalUrl != null) System.getenv("MYSQL_TEST_USERNAME") ?: "be2_test" else mysql.username }
            registry.add("spring.datasource.password") { if (externalUrl != null) System.getenv("MYSQL_TEST_PASSWORD") ?: error("MYSQL_TEST_PASSWORD is required") else mysql.password }
            registry.add("spring.flyway.enabled") { true }
        }
    }
}
