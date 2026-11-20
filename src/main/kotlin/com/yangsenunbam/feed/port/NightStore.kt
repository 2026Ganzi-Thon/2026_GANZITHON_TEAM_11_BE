package com.yangsenunbam.feed.port

import com.yangsenunbam.card.CardResponse
import com.yangsenunbam.common.ApiException
import com.yangsenunbam.common.CardTone
import com.yangsenunbam.common.CategoryId
import com.yangsenunbam.common.ErrorCode
import com.yangsenunbam.feed.model.*
import java.time.Instant
import java.time.LocalDate

/**
 * DB 확정 후 구현할 BE1/BE2/BE3 연결 지점.
 *
 * 사용자별 변경을 직렬화하고 block 전체를 하나의 트랜잭션으로 실행한다.
 * 예외(커밋 검증 포함)는 모든 쓰기를 롤백한다. 탈퇴/BE1 종료/BE3 카드 변경도
 * 같은 잠금 규약을 사용해야 한다. 카드 읽기는 삭제와의 경쟁을 막는 잠금이 필요하다.
 * 사용자·밤별 양 하나와 사용자·resume 키별 결과 하나는 DB 고유 제약으로도 보장한다.
 * 블록이나 검증 콜백을 트랜잭션 밖으로 넘기거나 저장 전에 성공 응답을 반환하지 않는다.
 */
interface NightStore {
    fun <T> withUser(userId: String, block: (NightTransaction) -> T): T
}

interface NightTransaction {
    fun night(id: String): NightRecord?
    fun nightOn(date: LocalDate): NightRecord?
    fun session(id: String): FeedSession?
    fun sessions(nightId: String): List<FeedSession>

    /** BE1 소유 필드와 BE3 스티커 변경을 유실하지 않도록 같은 트랜잭션에서 매핑한다. */
    fun saveNight(night: NightRecord)
    fun saveSession(session: FeedSession)

    /** 삭제된 카드도 메타데이터를 보존한 available=false 자리표시자를 반환한다. */
    fun card(id: String): CardResponse

    /** 삭제된 본문을 보관하지 않고 메타데이터 이력으로 마감 당시의 채택 가능 여부를 제공한다. */
    fun cardAt(id: String, instant: Instant): CardAvailability

    /** BE1 카테고리 설정에 확정된 색 키만 반환하고 미정이면 null을 반환한다. */
    fun colorKey(category: CategoryId): String?

    /** 사용자와 resume 작업 범위로 격리한다. 다른 세션에 같은 키를 쓰면 충돌한다. */
    fun resumeReceipt(key: String): ResumeReceipt?
    fun saveResumeReceipt(key: String, receipt: ResumeReceipt)

    /** 실제 커밋 직전에 실행하고 실패 시 전체 트랜잭션을 롤백한다. */
    fun beforeCommit(check: () -> Unit)
}

data class CardAvailability(val id: String, val available: Boolean, val tone: CardTone?)

fun NightTransaction.ownedNight(userId: String, id: String): NightRecord =
    night(id)?.takeIf { it.userId == userId } ?: notFound()

fun NightTransaction.ownedSession(userId: String, id: String): FeedSession =
    session(id)?.takeIf { it.userId == userId } ?: notFound()

private fun notFound(): Nothing = throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "기록을 찾을 수 없습니다.")
