package com.ganzithon.sheep.common

import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.resource.NoResourceFoundException
import tools.jackson.core.exc.StreamReadException
import java.util.UUID

@RestControllerAdvice
class ApiExceptionHandler {
    private val logger = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(ApiException::class)
    fun business(ex: ApiException, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        val builder = ResponseEntity.status(ex.code.status)
        if (ex.code.status in setOf(429, 503) || ex.code == ErrorCode.REQUEST_IN_PROGRESS) builder.header("Retry-After", "1")
        return builder.body(ErrorResponse(ErrorDetail(ex.code.name, ex.message, ex.fields, requestId(request), ex.support)))
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun validation(ex: MethodArgumentNotValidException, request: HttpServletRequest): ResponseEntity<ErrorResponse> =
        business(ApiException(ErrorCode.VALIDATION_ERROR, "입력값을 확인해 주세요.",
            ex.bindingResult.fieldErrors.associate { error ->
                error.field.replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase() to
                    (error.defaultMessage ?: "유효하지 않은 값입니다.")
            }), request)

    @ExceptionHandler(HandlerMethodValidationException::class, ConstraintViolationException::class,
        MissingRequestHeaderException::class, MissingServletRequestParameterException::class,
        MethodArgumentTypeMismatchException::class)
    fun invalidRequest(request: HttpServletRequest) =
        business(ApiException(ErrorCode.VALIDATION_ERROR, "필수 입력값과 형식을 확인해 주세요."), request)

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadable(ex: HttpMessageNotReadableException, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        val malformed = ex.cause == null || generateSequence<Throwable>(ex) { it.cause }.any { it is StreamReadException }
        return business(ApiException(if (malformed) ErrorCode.MALFORMED_JSON else ErrorCode.VALIDATION_ERROR,
            "요청 본문과 필드 형식을 확인해 주세요."), request)
    }

    @ExceptionHandler(NoResourceFoundException::class)
    fun missing(request: HttpServletRequest) = business(ApiException(ErrorCode.RESOURCE_NOT_FOUND, "리소스를 찾을 수 없어요."), request)

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun method(request: HttpServletRequest) = business(ApiException(ErrorCode.METHOD_NOT_ALLOWED, "지원하지 않는 메서드입니다."), request)

    @ExceptionHandler(HttpMediaTypeNotSupportedException::class)
    fun media(request: HttpServletRequest) = business(ApiException(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "JSON 형식으로 요청해 주세요."), request)

    @ExceptionHandler(Exception::class)
    fun unexpected(ex: Exception, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        logger.error("request_id={} exception={}", requestId(request), ex.javaClass.name)
        return business(ApiException(ErrorCode.INTERNAL_ERROR, "요청을 처리하지 못했어요."), request)
    }
}

fun requestId(request: HttpServletRequest): String = request.getAttribute("request_id") as? String
    ?: UUID.randomUUID().toString().also { request.setAttribute("request_id", it) }
