package com.ganzithon.sheep.morning

import com.ganzithon.sheep.common.*
import com.ganzithon.sheep.security.CurrentUser
import com.ganzithon.sheep.sheep.SheepResponse
import org.springframework.stereotype.Service

// BE2
@Service
class MorningService {
    fun morning(user: CurrentUser, nightId: String?): MorningResponse = notImplemented()

    fun adopt(user: CurrentUser, nightId: String, request: AdoptionRequest): SheepResponse = notImplemented()

    fun reflect(user: CurrentUser, nightId: String, request: ReflectionRequest): ReflectionResponse = notImplemented()
}
