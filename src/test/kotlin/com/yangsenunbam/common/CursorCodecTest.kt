package com.yangsenunbam.common

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class CursorCodecTest {
    @Test
    fun `인코딩한 값을 그대로 복원한다`() {
        val cursor = CursorCodec.encode("2026-11-10", "sh_01")

        assertThat(CursorCodec.decode(cursor, 2)).containsExactly("2026-11-10", "sh_01")
    }

    @Test
    fun `부분 개수가 다르면 422`() {
        val cursor = CursorCodec.encode("only-one")

        assertThatThrownBy { CursorCodec.decode(cursor, 2) }
            .isInstanceOfSatisfying(ApiException::class.java) {
                assertThat(it.code).isEqualTo(ErrorCode.VALIDATION_ERROR)
                assertThat(it.fields).containsKey("cursor")
            }
    }

    @Test
    fun `base64가 아니면 422`() {
        assertThatThrownBy { CursorCodec.decode("%%%", 2) }.isInstanceOf(ApiException::class.java)
    }

    @Test
    fun `빈 cursor는 422`() {
        assertThatThrownBy { CursorCodec.decode("", 2) }.isInstanceOf(ApiException::class.java)
    }
}
