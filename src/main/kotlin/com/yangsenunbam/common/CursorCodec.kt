package com.yangsenunbam.common

import java.nio.charset.StandardCharsets
import java.util.Base64

object CursorCodec {
    private const val SEPARATOR = "\u001F"

    fun encode(vararg parts: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(parts.joinToString(SEPARATOR).toByteArray(StandardCharsets.UTF_8))

    fun decode(cursor: String, expectedParts: Int): List<String> {
        val joined = try {
            String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            throw invalid()
        }
        val parts = joined.split(SEPARATOR)
        if (parts.size != expectedParts || parts.any { it.isEmpty() }) throw invalid()
        return parts
    }

    fun invalid(): ApiException =
        ApiException(ErrorCode.VALIDATION_ERROR, "입력값을 확인해 주세요.", mapOf("cursor" to "올바르지 않은 cursor예요."))
}
