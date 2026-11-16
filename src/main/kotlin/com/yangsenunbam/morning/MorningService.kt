package com.yangsenunbam.morning

import com.yangsenunbam.common.*
import com.yangsenunbam.security.CurrentUser
import com.yangsenunbam.flock.SheepResponse
import org.springframework.stereotype.Service

// BE2
@Service
class MorningService {
    fun morning(user: CurrentUser, nightId: String?): MorningResponse = notImplemented()

    fun adopt(user: CurrentUser, nightId: String, request: AdoptionRequest): SheepResponse = notImplemented()

    fun reflect(user: CurrentUser, nightId: String, request: ReflectionRequest): ReflectionResponse = notImplemented()
}
