package com.reagent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class TimeConfiguration {
    @Bean
    Clock reagentClock() {
        return Clock.systemUTC();
    }
}
