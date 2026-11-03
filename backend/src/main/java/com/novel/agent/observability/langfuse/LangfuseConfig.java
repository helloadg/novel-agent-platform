package com.novel.agent.observability.langfuse;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Langfuse 可观测性装配
 * <p>
 * 默认不启用 (langfuse.enabled=false), 配了 URL/Key 并开启后才生效。
 * 内部用一个独立的线程池执行异步 ingestion, 不影响主业务 Tomcat 线程。
 */
@Configuration
@EnableAsync
@EnableConfigurationProperties(LangfuseProperties.class)
public class LangfuseConfig {

    @Bean("langfuseTaskExecutor")
    @ConditionalOnProperty(name = "langfuse.enabled", havingValue = "true")
    public Executor langfuseTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(256);
        executor.setThreadNamePrefix("langfuse-ingest-");
        // 主线程也兜底跑, 避免队列满时丢事件
        executor.setRejectedExecutionHandler((r, e) -> r.run());
        executor.initialize();
        return executor;
    }
}
