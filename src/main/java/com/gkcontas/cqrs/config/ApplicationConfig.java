package com.gkcontas.cqrs.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ApplicationConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
