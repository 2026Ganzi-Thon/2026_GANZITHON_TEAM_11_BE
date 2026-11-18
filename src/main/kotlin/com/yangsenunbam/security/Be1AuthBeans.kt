package com.yangsenunbam.security

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import java.time.Clock

@Configuration
class Be1AuthBeans {
    @Bean fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder(12)
    @Bean fun authClock(): Clock = Clock.systemUTC()
}
