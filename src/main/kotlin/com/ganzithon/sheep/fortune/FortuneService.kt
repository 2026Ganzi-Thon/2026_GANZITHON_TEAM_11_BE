package com.ganzithon.sheep.fortune

import com.ganzithon.sheep.common.*
import com.ganzithon.sheep.security.CurrentUser
import org.springframework.stereotype.Service

// BE2
@Service
class FortuneService {
    fun today(user: CurrentUser): FortuneResponse = notImplemented()
}
