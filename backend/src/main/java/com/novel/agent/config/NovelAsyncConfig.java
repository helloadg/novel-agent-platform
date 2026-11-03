package com.novel.agent.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 小说写作异步执行配置
 * <p>
 * 一部作品要串行跑几十次模型调用 (远超 HTTP 超时), 因此生成过程放到独立线程池,
 * 接口只负责触发并立即返回; 前端通过轮询接口获取进度。
 * 线程池容量刻意压小: 写作任务是长任务 + 有 LLM 调用成本, 并发过高会拖垮网关。
 */
@Slf4j
@Configuration
@EnableAsync
public class NovelAsyncConfig {

    @Bean("novelExecutor")
    public Executor novelExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(20);
        executor.setKeepAliveSeconds(120);
        executor.setThreadNamePrefix("novel-");
        // 队列满时由调用线程执行, 保证任务不丢失 (前端会提示"排队中")
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        log.info("[novel] 小说写作线程池初始化: core=2 max=4 queue=20");
        return executor;
    }
}
