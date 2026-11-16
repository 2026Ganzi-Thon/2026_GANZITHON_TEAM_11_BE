package com.yangsenunbam.fortune

import com.yangsenunbam.common.*
import com.yangsenunbam.security.CurrentUser
import org.springframework.stereotype.Service

// BE2
@Service
class FortuneService {
    fun today(user: CurrentUser): FortuneResponse = notImplemented()
}
