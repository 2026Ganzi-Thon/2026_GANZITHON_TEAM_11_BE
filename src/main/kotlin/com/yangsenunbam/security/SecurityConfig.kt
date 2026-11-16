package com.yangsenunbam.security

import com.yangsenunbam.common.ErrorDetail
import com.yangsenunbam.common.ErrorResponse
import com.yangsenunbam.common.requestId
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authorization.AuthorizationDecision
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import tools.jackson.databind.json.JsonMapper

@Configuration
class SecurityConfig {
    // BE1에서 Bearer 토큰 검증과 CurrentUser를 만드는 인증 필터를 연결한다.
    @Bean
    fun authenticationManager(): AuthenticationManager = AuthenticationManager {
        throw BadCredentialsException("Authentication is not implemented")
    }

    @Bean
    fun securityFilterChain(http: HttpSecurity, mapper: JsonMapper): SecurityFilterChain {
        fun error(req: HttpServletRequest, res: HttpServletResponse, status: Int, code: String, message: String) {
            res.status = status
            res.contentType = "application/json"
            res.characterEncoding = "UTF-8"
            mapper.writeValue(res.writer, ErrorResponse(ErrorDetail(code, message, emptyMap(), requestId(req))))
        }
        http.csrf { it.disable() }
            .formLogin { it.disable() }
            .httpBasic { it.disable() }
            .logout { it.disable() }
            .requestCache { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it.requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/refresh",
                    "/api/v1/auth/password-reset/request", "/api/v1/auth/password-reset/confirm").permitAll()
                it.requestMatchers(HttpMethod.GET, "/api/v1/legal-documents").permitAll()
                it.requestMatchers(HttpMethod.GET, "/api/v1/bootstrap").authenticated()
                it.requestMatchers(HttpMethod.PUT, "/api/v1/me/consents", "/api/v1/me/birth-date").authenticated()
                it.requestMatchers(HttpMethod.POST, "/api/v1/auth/logout").authenticated()
                it.requestMatchers(HttpMethod.DELETE, "/api/v1/me").authenticated()
                it.requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                it.anyRequest().access { authentication, _ ->
                    val user = authentication.get().principal as? CurrentUser
                    AuthorizationDecision(user?.onboardingComplete == true)
                }
            }
            .exceptionHandling {
                it.authenticationEntryPoint { req, res, _ -> error(req, res, 401, "UNAUTHORIZED", "로그인이 필요해요.") }
                it.accessDeniedHandler { req, res, _ ->
                    val admin = req.requestURI.startsWith("/api/v1/admin/")
                    error(req, res, 403, if (admin) "ADMIN_REQUIRED" else "ONBOARDING_REQUIRED",
                        if (admin) "운영자 권한이 필요해요." else "약관 동의와 프로필 입력을 완료해 주세요.")
                }
            }
        return http.build()
    }
}
