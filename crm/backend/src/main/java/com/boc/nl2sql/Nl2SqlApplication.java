package com.boc.nl2sql;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 个金营销 NL2SQL 模块化单体启动入口。
 *
 * <p>各业务模块在同一进程内通过 Java 接口直接调用，避免 MVP 阶段引入不必要的 RPC。</p>
 */
@EnableAsync
// 登录只使用数据库账户与自有 JWT，排除 Spring 生成的临时内存用户。
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class Nl2SqlApplication {

    public static void main(String[] args) {
        SpringApplication.run(Nl2SqlApplication.class, args);
    }
}
