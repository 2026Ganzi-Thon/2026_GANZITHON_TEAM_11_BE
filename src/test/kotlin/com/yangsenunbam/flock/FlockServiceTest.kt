package com.yangsenunbam.flock

import com.yangsenunbam.be2.MutableClock
import com.yangsenunbam.bootstrap.KstNightTime
import com.yangsenunbam.common.ApiException
import com.yangsenunbam.common.CursorCodec
import com.yangsenunbam.common.ErrorCode
import com.yangsenunbam.security.CurrentUser
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

class FlockServiceTest {
    private val user = CurrentUser("u_1", true)
    private val d9 = LocalDate.parse("2026-11-09")
    private val store = FakeFlockStore()
    private val clock = MutableClock(Instant.parse("2026-11-11T03:00:00Z")) // 12:00 KST, 낮
    private val service = FlockService(store, KstNightTime(clock))

    @Test
    fun `cursor로 끝까지 넘기면 night_date·id 내림차순으로 빠짐없이 중복없이 조회된다`() {
        for (i in 1..5) store.add("u_1", "sh_$i", d9.plusDays((i % 3).toLong()))
        store.add("u_2", "sh_x", d9.plusDays(5))

        val seen = mutableListOf<String>()
        var cursor: String? = null
        var pages = 0
        do {
            val page = service.list(user, cursor, 2)
            seen += page.items.map { it.id }
            cursor = page.nextCursor
            pages++
        } while (cursor != null)

        assertThat(pages).isEqualTo(3)
        // night_date: sh_3=d9, sh_1·sh_4=d10, sh_2·sh_5=d11
        assertThat(seen).containsExactly("sh_5", "sh_2", "sh_4", "sh_1", "sh_3")
    }

    @Test
    fun `마지막 페이지가 정확히 limit개면 next_cursor는 null`() {
        for (i in 1..2) store.add("u_1", "sh_$i", d9)

        val page = service.list(user, null, 2)

        assertThat(page.items).hasSize(2)
        assertThat(page.nextCursor).isNull()
    }

    @Test
    fun `양이 없으면 빈 items와 null cursor`() {
        val page = service.list(user, null, 20)

        assertThat(page.items).isEmpty()
        assertThat(page.nextCursor).isNull()
    }

    @Test
    fun `형식이 틀린 cursor는 422 fields_cursor`() {
        for (cursor in listOf("!!!", "", "bm90LWEtY3Vyc29y", CursorCodec.encode("2026-13-45", "sh_1"))) {
            assertThatThrownBy { service.list(user, cursor, 20) }
                .isInstanceOfSatisfying(ApiException::class.java) {
                    assertThat(it.code).isEqualTo(ErrorCode.VALIDATION_ERROR)
                    assertThat(it.fields).containsKey("cursor")
                }
        }
    }

    @Test
    fun `남의 양이나 없는 양은 404 RESOURCE_NOT_FOUND`() {
        assertThatThrownBy { service.detail(user, "sh_none") }
            .isInstanceOfSatisfying(ApiException::class.java) {
                assertThat(it.code).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND)
            }
    }

    @Test
    fun `스티커는 저장소 순서 그대로 items로 돌려준다`() {
        store.stickers += listOf(StickerResponse("st_1", "달", "a"), StickerResponse("st_2", "구름", "b"))

        assertThat(service.stickers(user).items.map { it.id }).containsExactly("st_1", "st_2")
    }

    @Test
    fun `스티커 배치는 낮에 전체 교체되고 version이 1 오른다`() {
        store.add("u_1", "sh_1", d9)
        store.stickers += StickerResponse("st_1", "달", "a")

        val saved = service.saveStickers(user, "sh_1", SaveStickersRequest(1, listOf(placement("pl_1", "st_1"))))

        assertThat(saved.version).isEqualTo(2)
        assertThat(saved.placements.map { it.instanceId }).containsExactly("pl_1")
        assertThat(store.lockSheep("u_1", "sh_1")!!.version).isEqualTo(2)
    }

    @Test
    fun `스티커 배치 오류 - 남의 양 404, 밤 DAY_ONLY, 이전 version VERSION_CONFLICT, 없는 스티커 422`() {
        store.add("u_2", "sh_x", d9)
        store.add("u_1", "sh_1", d9)
        store.stickers += StickerResponse("st_1", "달", "a")
        val ok = SaveStickersRequest(1, listOf(placement("pl_1", "st_1")))

        assertCode(ErrorCode.RESOURCE_NOT_FOUND) { service.saveStickers(user, "sh_x", ok) }
        assertCode(ErrorCode.VERSION_CONFLICT) { service.saveStickers(user, "sh_1", SaveStickersRequest(2, emptyList())) }
        assertCode(ErrorCode.VALIDATION_ERROR) {
            service.saveStickers(user, "sh_1", SaveStickersRequest(1, listOf(placement("pl_1", "st_none"))))
        }
        clock.current = Instant.parse("2026-11-11T12:00:00Z") // 21:00 KST
        assertCode(ErrorCode.DAY_ONLY) { service.saveStickers(user, "sh_1", ok) }
    }

    private fun placement(instanceId: String, stickerId: String) = Placement(
        instanceId, stickerId, java.math.BigDecimal("0.4"), java.math.BigDecimal("0.6"), java.math.BigDecimal.ZERO, java.math.BigDecimal.ONE)

    private fun assertCode(code: ErrorCode, block: () -> Unit) {
        assertThatThrownBy(block).isInstanceOfSatisfying(ApiException::class.java) { assertThat(it.code).isEqualTo(code) }
    }

    /** 저장소 계약(정렬·cursor·소유자)을 그대로 흉내 내는 메모리 구현. */
    private class FakeFlockStore : FlockStore {
        private val sheep = mutableListOf<Pair<String, SheepResponse>>()
        val stickers = mutableListOf<StickerResponse>()

        fun add(userId: String, id: String, nightDate: LocalDate) {
            sheep += userId to SheepResponse(id, "n_$id", nightDate, "c_1", null, Instant.EPOCH, emptyList(), 1)
        }

        override fun sheepPage(userId: String, after: SheepCursor?, limit: Int): List<SheepResponse> =
            sheep.filter { it.first == userId }.map { it.second }
                .sortedWith(compareByDescending<SheepResponse> { it.nightDate }.thenByDescending { it.id })
                .filter { after == null || it.nightDate < after.nightDate || (it.nightDate == after.nightDate && it.id < after.id) }
                .take(limit)

        override fun sheepDetail(userId: String, sheepId: String): SheepDetailResponse? = null

        override fun stickers(): List<StickerResponse> = stickers

        override fun lockSheep(userId: String, sheepId: String): SheepResponse? =
            sheep.firstOrNull { it.first == userId && it.second.id == sheepId }?.second

        override fun unknownStickers(stickerIds: Set<String>): Set<String> = stickerIds - stickers.map { it.id }.toSet()

        override fun savePlacements(sheepId: String, placements: List<Placement>, version: Int) {
            val index = sheep.indexOfFirst { it.second.id == sheepId }
            sheep[index] = sheep[index].first to sheep[index].second.copy(placements = placements, version = version)
        }
    }
}
