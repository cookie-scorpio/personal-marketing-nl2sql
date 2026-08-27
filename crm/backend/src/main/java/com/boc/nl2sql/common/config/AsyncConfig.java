package com.boc.nl2sql.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class AsyncConfig {

    /** 查询任务使用独立线程池，避免模型/数据库慢调用占满 Web 请求线程。 */
    @Bean(name = "queryExecutor")
    Executor queryExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(6);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("nl2sql-query-");
        executor.initialize();
        return executor;
    }
}
