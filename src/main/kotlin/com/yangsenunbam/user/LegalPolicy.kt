package com.yangsenunbam.user

import com.yangsenunbam.common.*
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI

/** Live legal-document URLs/versions must come from approved team content. */
@Component
class LegalPolicy(
    @Value("\${app.legal.terms-version:}") val termsVersion: String,
    @Value("\${app.legal.privacy-version:}") val privacyVersion: String,
    @Value("\${app.legal.terms-url:}") private val termsUrl: String,
    @Value("\${app.legal.privacy-url:}") private val privacyUrl: String,
) {
    fun hasVersions() = termsVersion.isNotBlank() && privacyVersion.isNotBlank()

    fun requireDocuments(): ItemsResponse<LegalDocumentResponse> {
        if (!hasVersions() || !validUrl(termsUrl) || !validUrl(privacyUrl)) {
            throw ApiException(ErrorCode.NOT_IMPLEMENTED, "팀에서 승인한 약관과 개인정보 처리 문서 URL 설정이 필요합니다.")
        }
        return ItemsResponse(listOf(
            LegalDocumentResponse(LegalDocumentType.TERMS, termsVersion, "이용약관", termsUrl, true),
            LegalDocumentResponse(LegalDocumentType.PRIVACY, privacyVersion, "개인정보 처리방침", privacyUrl, true),
        ))
    }

    private fun validUrl(raw: String) = runCatching {
        val uri = URI(raw)
        uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.userInfo == null
    }.getOrDefault(false)
}
