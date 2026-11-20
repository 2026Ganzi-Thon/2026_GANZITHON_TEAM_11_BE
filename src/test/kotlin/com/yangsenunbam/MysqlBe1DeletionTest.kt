package com.yangsenunbam

import com.yangsenunbam.auth.AuthStore
import com.yangsenunbam.common.*
import com.yangsenunbam.flock.FlockService
import com.yangsenunbam.morning.MorningService
import com.yangsenunbam.morning.AdoptionRequest
import com.yangsenunbam.morning.ReflectionRequest
import com.yangsenunbam.night.NightService
import com.yangsenunbam.security.CurrentUser
import com.yangsenunbam.user.UserService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag
import org.mockito.Mockito.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.convention.TestBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.util.AopTestUtils
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.*
import java.util.UUID
import java.util.concurrent.*

@Tag("mysql")
@SpringBootTest
@ActiveProfiles("mysql")
class MysqlBe1DeletionTest {
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var users: UserService
    @Autowired lateinit var nights: NightService
    @Autowired lateinit var morning: MorningService
    @Autowired lateinit var flock: FlockService
    @Autowired lateinit var manager: PlatformTransactionManager
    @MockitoSpyBean lateinit var auth: AuthStore
    @TestBean(methodName = "fixedClock") lateinit var clock: Clock

    data class Fixture(val user: CurrentUser, val night: String, val session: String, val card: String, val sheep: String)

    private fun fixture(cardOwner: String? = null): Fixture {
        val user = UUID.randomUUID().toString()
        val night = UUID.randomUUID().toString()
        val session = UUID.randomUUID().toString()
        val card = "card-${UUID.randomUUID()}"
        val sheep = "sheep-${UUID.randomUUID()}"
        jdbc.update("INSERT INTO auth_users(id,email,password_hash,created_at_ms) VALUES (?,?,?,0)", user, "$user@example.test", "fixture")
        jdbc.update("INSERT INTO user_onboarding(user_id) VALUES (?)", user)
        jdbc.update("INSERT INTO be1_nights(id,user_id,night_date,category_id,worry_text,worry_saved_at_ms) VALUES (?,?,'2026-11-16','other','fixture worry',0)", night,user)
        jdbc.update("""INSERT INTO be1_sessions(id,night_id,user_id,status,sheep_count,feed_version,position,furthest_position,started_at_ms,closes_at_ms)
            VALUES (?,?,?,'completed',3,1,3,3,0,1)""",session,night,user)
        jdbc.update("""INSERT INTO feed_card_catalog(id,kind,tone,category_id,author_type,author_label,author_user_id,content_json,available,published_at_ms)
            VALUES (?,'text','warm','other','user','fixture author',?,'{"text":"private fixture"}',TRUE,0)""",card,cardOwner ?: user)
        jdbc.update("INSERT INTO feed_card_history VALUES (?,0,TRUE,'warm')", card)
        jdbc.update("INSERT INTO night_feed_state(night_id,reflection_answer,reflection_message) VALUES (?,'same','fixture reflection')",night)
        for (position in 1..3) jdbc.update("INSERT INTO feed_items(id,session_id,position,card_id) VALUES (?,?,?,?)", UUID.randomUUID().toString(),session,position,card)
        jdbc.update("INSERT INTO feed_card_views VALUES (?,?,3,3,0,1)",session,card)
        jdbc.update("INSERT INTO feed_session_progress VALUES (?,3,0,NULL,NULL)",session)
        jdbc.update("INSERT INTO night_reactions VALUES (?,?,TRUE,NULL)",night,card)
        jdbc.update("INSERT INTO feed_card_yarn VALUES (?,?)",user,card)
        jdbc.update("""INSERT INTO adopted_sheep(id,night_id,user_id,night_date,card_id,color_key,created_at_ms,position,sheep_count,placements_json,version)
            VALUES (?,?,?,'2026-11-16',?,'gray',0,3,3,'[]',1)""",sheep,night,user,card)
        jdbc.update("INSERT INTO feed_resume_receipts VALUES (?,'fixture-key',?,'{}',9999999999999)",user,session)
        return Fixture(CurrentUser(user,true),night,session,card,sheep)
    }

    private fun count(table: String, column: String, id: String) = jdbc.queryForObject("SELECT COUNT(*) FROM $table WHERE $column = ?", Int::class.java,id)!!
    private fun reflection(f: Fixture) = jdbc.queryForObject("SELECT reflection_answer FROM night_feed_state WHERE night_id = ?",String::class.java,f.night)

    @Test fun `worry deletion removes reflection and preserves sheep and reactions`() {
        val f=fixture()
        repeat(2) { nights.deleteWorry(f.user,f.night) }
        assertNull(reflection(f))
        assertNull(jdbc.queryForObject("SELECT reflection_message FROM night_feed_state WHERE night_id = ?",String::class.java,f.night))
        assertNull(flock.detail(f.user,f.sheep).night.worryText)
        assertNull(flock.detail(f.user,f.sheep).night.reflection)
        assertEquals(1,count("adopted_sheep","id",f.sheep))
        assertEquals(1,count("night_reactions","night_id",f.night))
    }

    @Test fun `deletion also repairs previously deleted worry and rejects another owner`() {
        val f=fixture(); val other=fixture()
        val error=assertThrows(ApiException::class.java) { nights.deleteWorry(other.user,f.night) }
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND,error.code)
        assertEquals("same",reflection(f))
        jdbc.update("UPDATE be1_nights SET worry_deleted=TRUE,worry_text=NULL WHERE id=?",f.night)
        nights.deleteWorry(f.user,f.night)
        assertNull(reflection(f))
    }

    @Test fun `account deletion cascades own data and hides authored card in another flock`() {
        val owner=fixture(); val reader=fixture()
        jdbc.update("UPDATE adopted_sheep SET card_id=? WHERE id=?",owner.card,reader.sheep)
        jdbc.update("UPDATE be1_sessions SET closing_card_json=? WHERE id=?",
            """{"id":"${owner.card}","content":{"text":"private fixture"},"author_label":"fixture author","available":true}""", reader.session)
        jdbc.update("INSERT INTO feed_card_yarn VALUES (?,?)",reader.user.id,owner.card)
        users.deleteAccount(owner.user)
        assertEquals(0,count("auth_users","id",owner.user.id))
        assertEquals(0,count("be1_nights","id",owner.night))
        for (table in listOf("feed_items","feed_card_views","feed_session_progress","feed_resume_receipts"))
            assertEquals(0,count(table,"session_id",owner.session),table)
        for (table in listOf("night_feed_state","night_reactions","adopted_sheep"))
            assertEquals(0,count(table,"night_id",owner.night),table)
        assertEquals(0,count("feed_card_yarn","user_id",owner.user.id))
        assertEquals(0,count("feed_card_yarn","card_id",owner.card))
        val detail=flock.detail(reader.user,reader.sheep)
        assertFalse(detail.card.available); assertNull(detail.card.content); assertNull(detail.card.authorLabel)
        assertNull(jdbc.queryForObject("SELECT author_user_id FROM feed_card_catalog WHERE id=?",String::class.java,owner.card))
        assertEquals(1,count("auth_users","id",reader.user.id))
        val snapshot=jdbc.queryForObject("SELECT closing_card_json FROM be1_sessions WHERE id=?",String::class.java,reader.session)!!
        assertFalse(snapshot.contains("private fixture"))
        assertFalse(snapshot.contains("fixture author"))
    }

    @Test fun `outer transaction failure rolls back all domains`() {
        val f=fixture()
        assertThrows(IllegalStateException::class.java) {
            TransactionTemplate(manager).executeWithoutResult { users.deleteAccount(f.user); error("fixture rollback") }
        }
        assertEquals(1,count("auth_users","id",f.user.id))
        assertEquals(1,count("feed_items","id",jdbc.queryForList("SELECT id FROM feed_items WHERE session_id=?",String::class.java,f.session).first()!!))
        assertTrue(flock.detail(f.user,f.sheep).card.available)
        assertEquals("same",reflection(f))
    }

    /** Hold the real leader transaction while the contender reaches its account lock. */
    private fun race(leader: () -> Unit, contender: () -> Unit): Throwable? {
        val entered=CountDownLatch(1)
        val target=AopTestUtils.getUltimateTargetObject<AuthStore>(auth)
        doAnswer { invocation ->
            if (Thread.currentThread().name=="be1-delete-contender") entered.countDown()
            invocation.callRealMethod()
        }.`when`(target).lockUser(anyString())
        val executor=Executors.newSingleThreadExecutor { runnable -> Thread(runnable,"be1-delete-contender") }
        var future: Future<Throwable?>?=null
        try {
            TransactionTemplate(manager).executeWithoutResult {
                leader()
                future=executor.submit(Callable { try { contender(); null } catch (e: Throwable) { e } })
                assertTrue(entered.await(5,TimeUnit.SECONDS))
                assertThrows(TimeoutException::class.java) { future!!.get(150,TimeUnit.MILLISECONDS) }
            }
            return future!!.get(10,TimeUnit.SECONDS)
        } finally { executor.shutdownNow() }
    }

    @Test fun `reflection first then deletion cannot resurrect reflection`() {
        val f=fixture()
        assertNull(race({ morning.reflect(f.user,f.night,ReflectionRequest(ReflectionAnswer.SMALLER)) }, { nights.deleteWorry(f.user,f.night) }))
        assertNull(reflection(f))
    }

    @Test fun `deletion first then reflection rejects stale write`() {
        val f=fixture()
        // BE2 locks auth_users directly, so observe its SQL lock through completion rather
        // than the AuthStore spy used for a BE1 contender.
        val executor=Executors.newSingleThreadExecutor()
        val started=CountDownLatch(1)
        var future: Future<Throwable?>?=null
        try {
            TransactionTemplate(manager).executeWithoutResult {
                nights.deleteWorry(f.user,f.night)
                future=executor.submit(Callable { started.countDown(); try { morning.reflect(f.user,f.night,ReflectionRequest(ReflectionAnswer.SAME)); null } catch(e: Throwable) { e } })
                assertTrue(started.await(5,TimeUnit.SECONDS))
                assertThrows(TimeoutException::class.java) { future!!.get(150,TimeUnit.MILLISECONDS) }
            }
            assertEquals(ErrorCode.REFLECTION_NOT_AVAILABLE,(future!!.get(10,TimeUnit.SECONDS) as ApiException).code)
            assertNull(reflection(f))
        } finally { executor.shutdownNow() }
    }

    @Test fun `account deletion waits for in flight reflection and removes its committed state`() {
        val f=fixture()
        assertNull(race({ morning.reflect(f.user,f.night,ReflectionRequest(ReflectionAnswer.SMALLER)) }, { users.deleteAccount(f.user) }))
        assertEquals(0,count("night_feed_state","night_id",f.night))
        assertEquals(0,count("auth_users","id",f.user.id))
    }

    @Test fun `account deletion waits for adoption and cascades the newly adopted sheep`() {
        val f=fixture()
        jdbc.update("DELETE FROM adopted_sheep WHERE id=?",f.sheep)
        assertNull(race({ morning.adopt(f.user,f.night,AdoptionRequest(f.card)) }, { users.deleteAccount(f.user) }))
        assertEquals(0,count("adopted_sheep","user_id",f.user.id))
        assertEquals(0,count("auth_users","id",f.user.id))
    }

    @Test fun `unavailable authored card still loses remaining identity and body`() {
        val f=fixture()
        jdbc.update("UPDATE feed_card_catalog SET available=FALSE WHERE id=?",f.card)
        users.deleteAccount(f.user)
        val row=jdbc.queryForMap("SELECT content_json,author_label,author_user_id FROM feed_card_catalog WHERE id=?",f.card)
        assertTrue(row.values.all { it==null })
        assertEquals(0,count("feed_card_yarn","card_id",f.card))
    }

    companion object {
        @JvmStatic fun fixedClock(): Clock = Clock.fixed(Instant.parse("2026-11-17T03:00:00Z"),ZoneOffset.UTC)
        @JvmStatic @DynamicPropertySource fun database(registry: DynamicPropertyRegistry) {
            val url=System.getenv("MYSQL_TEST_URL") ?: error("MYSQL_TEST_URL is required")
            require(Regex("jdbc:mysql://(?:localhost|127\\.0\\.0\\.1):[0-9]+/[A-Za-z0-9_]+_test(?:\\?.*)?").matches(url))
            registry.add("spring.datasource.url") { url }
            registry.add("spring.datasource.username") { System.getenv("MYSQL_TEST_USERNAME") ?: "root" }
            registry.add("spring.datasource.password") { System.getenv("MYSQL_TEST_PASSWORD") ?: "" }
        }
    }
}
