package io.github.rahulkumar96t.ledger.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ClockConfiguration {

    /** UTC wall clock; tests replace it with a controllable one. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
