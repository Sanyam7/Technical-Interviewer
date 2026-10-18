package com.techinterviewer.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables {@code @Scheduled} jobs (currently only the refresh-token purge). */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
