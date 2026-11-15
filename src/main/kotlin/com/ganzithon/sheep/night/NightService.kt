package com.ganzithon.sheep.night

import com.ganzithon.sheep.common.*
import com.ganzithon.sheep.security.CurrentUser
import org.springframework.stereotype.Service

// BE1
@Service
class NightService {
    fun categories(user: CurrentUser): ItemsResponse<CategoryResponse> = notImplemented()

    fun recordWorry(user: CurrentUser, request: WorryRequest): NightResponse = notImplemented()

    fun continueAfterSupport(user: CurrentUser, nightId: String): NightResponse = notImplemented()

    fun saveWritingOffer(user: CurrentUser, nightId: String, request: WritingOfferRequest): WritingOfferResponse = notImplemented()

    fun deleteWorry(user: CurrentUser, nightId: String): Unit = notImplemented()
}
