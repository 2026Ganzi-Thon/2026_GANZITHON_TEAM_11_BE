package com.yangsenunbam.flock

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
    private val service = FlockService(store)

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
    }
}
