package com.yangsenunbam.security

import com.yangsenunbam.auth.AuthStore
import com.yangsenunbam.user.OnboardingStore
import com.yangsenunbam.user.LegalPolicy
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Clock

class BearerTokenFilter(
    private val tokens: TokenService,
    private val store: AuthStore,
    private val onboarding: OnboardingStore,
    private val policy: LegalPolicy,
    private val clock: Clock,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val header = request.getHeader("Authorization")
        if (header?.startsWith("Bearer ") == true) {
            val nowMs = clock.millis()
            val claims = tokens.verifyAccess(header.removePrefix("Bearer ").trim(), nowMs / 1000)
            // A repeated logout must still return 204 for a previously revoked session.
            // This exception applies ONLY to logout; revoked access tokens are rejected elsewhere.
            val logoutRetry = request.method == "POST" &&
                request.requestURI.removePrefix(request.contextPath) == "/api/v1/auth/logout"
            if (claims != null && (store.activeSession(claims.sessionId, claims.userId, nowMs) || logoutRetry)) {
                val user = store.findUserById(claims.userId)
                if (user != null) {
                    val principal = CurrentUser(user.id, onboarding.completed(user.id, policy))
                    val auth = UsernamePasswordAuthenticationToken(
                        principal, null, listOf(SimpleGrantedAuthority("ROLE_USER")),
                    )
                    SecurityContextHolder.getContext().authentication = auth
                }
            }
        }
        filterChain.doFilter(request, response)
    }
}
