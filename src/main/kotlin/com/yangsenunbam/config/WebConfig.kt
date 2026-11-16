package com.yangsenunbam.config

import com.yangsenunbam.common.*
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Configuration
import org.springframework.format.FormatterRegistry
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class WebConfig : WebMvcConfigurer {
    override fun addFormatters(registry: FormatterRegistry) {
        registry.addConverter(String::class.java, ModerationStatus::class.java) { value ->
            ModerationStatus.entries.find { it.value == value }
                ?: throw IllegalArgumentException("Invalid moderation status")
        }
    }

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(object : HandlerInterceptor {
            override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
                if (handler is HandlerMethod && handler.hasMethodAnnotation(NoRequestBody::class.java) && request.inputStream.read() != -1)
                    throw ApiException(ErrorCode.VALIDATION_ERROR, "이 요청은 본문을 받지 않습니다.")
                return true
            }
        })
    }
}
