package com.boc.nl2sql.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/** 质量事实的线程和定时任务配置，与查询执行线程池相互隔离。 */
@Configuration
@EnableScheduling
public class QualityConfiguration {
    /**
     * 创建事实持久化专用执行器。
     *
     * <p>单核心线程保证通常情况下的提交顺序；队列积压时最多扩展到两个线程。
     * 队列拒绝由 {@code AsyncQualityFacts} 捕获并转入本地补偿文件。</p>
     */
    @Bean(name = "qualityEventExecutor")
    Executor qualityEventExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("quality-event-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }

    /**
     * 评测运行专用执行器。评测逐条重放问题并真实调用模型与数据库，串行执行
     * 避免多个运行同时压垮模型配额；队列容量有限，提交过多时直接拒绝并提示稍后重试。
     */
    @Bean(name = "evaluationExecutor")
    Executor evaluationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("eval-run-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}
