package com.reagent.config;

import com.reagent.core.FaultInjector;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Production-safe fault injection default; tests and demo-chaos may supply an explicit bean. */
@Configuration
public class FaultConfiguration {

    @Bean
    @ConditionalOnMissingBean(FaultInjector.class)
    FaultInjector faultInjector() {
        return FaultInjector.none();
    }
}
