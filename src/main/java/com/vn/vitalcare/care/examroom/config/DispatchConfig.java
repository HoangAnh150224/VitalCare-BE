package com.vn.vitalcare.care.examroom.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * The threads automatic dispatching runs on.
 *
 * <p>Its own pool, so a slow database under a burst of check-ins queues work
 * here instead of holding request threads. Every task ends in
 * {@code RoomDispatcher.dispatch}, which is safe however many run at once, so
 * the pool can be widened ({@code app.dispatch.pool-size}) without anything
 * else changing.
 */
@Configuration
@EnableAsync
@EnableScheduling
public class DispatchConfig {

    public static final String EXECUTOR = "dispatchExecutor";

    @Bean(name = EXECUTOR)
    public ThreadPoolTaskExecutor dispatchExecutor(@Value("${app.dispatch.pool-size:4}") int poolSize) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("dispatch-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}
