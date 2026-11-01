package io.github.Michal_Antosiewicz.apiary_management_system.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
class TimeConfiguration {
    @Bean
    fun applicationClock(): Clock = Clock.systemUTC()
}
