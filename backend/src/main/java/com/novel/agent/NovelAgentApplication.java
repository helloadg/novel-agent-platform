package com.novel.agent;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 多角色小说写作智能体平台 - 启动类
 * 集成: LangChain4j / LangGraph4j / PostgreSQL / MyBatis-Plus / Prometheus
 */
@SpringBootApplication
@MapperScan("com.novel.agent.mapper")
public class NovelAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(NovelAgentApplication.class, args);
    }
}
