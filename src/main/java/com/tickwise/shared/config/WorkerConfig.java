package com.tickwise.shared.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The same artifact runs as the API (default) or as the background worker ({@code worker}
 * profile). Only the worker schedules background loops: escalations, inbound email and the
 * outbound email outbox. Each module contributes its own {@code @Profile("worker")} component.
 */
@Configuration(proxyBeanMethods = false)
@Profile("worker")
@EnableScheduling
class WorkerConfig {}
