package com.yangsenunbam.common

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

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
