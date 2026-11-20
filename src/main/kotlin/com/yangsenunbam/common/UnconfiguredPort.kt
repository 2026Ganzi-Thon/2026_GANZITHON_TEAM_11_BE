package com.yangsenunbam.common

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

/**
 * 저장소 어댑터가 없는 프로필(기본 H2)에서 쓰는 대체 구현. 모든 호출이 501 NOT_IMPLEMENTED가 된다.
 * MySQL 어댑터가 `@Profile("mysql")`로만 등록되는 파트가 스켈레톤 계약(501)을 유지할 때 사용한다.
 */
inline fun <reified T : Any> unconfiguredPort(message: String): T {
    val type = T::class.java
    val handler = InvocationHandler { proxy, method, args ->
        when (method.name) {
            "toString" -> "Unconfigured(${type.simpleName})"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            else -> throw ApiException(ErrorCode.NOT_IMPLEMENTED, message)
        }
    }
    return type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type), handler))
}
