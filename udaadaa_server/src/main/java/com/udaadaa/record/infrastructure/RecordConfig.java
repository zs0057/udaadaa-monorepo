package com.udaadaa.record.infrastructure;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(CalorieApiProperties.class)
class RecordConfig {
}
