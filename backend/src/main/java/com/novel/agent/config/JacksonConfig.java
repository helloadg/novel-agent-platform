package com.novel.agent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson 序列化配置
 * —— 让 Long / Long[] 序列化为 String, 避免雪花 ID 超过 JS Number 安全整数范围 (±9,007,199,254,740,992) 导致前端精度丢失
 * —— int / double / BigDecimal 保持原样 (前端仍为 number)
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer longAsStringCustomizer() {
        return builder -> {
            SimpleModule module = new SimpleModule("LongAsStringModule");
            module.addSerializer(Long.class, ToStringSerializer.instance);
            module.addSerializer(Long.TYPE, ToStringSerializer.instance);
            builder.modulesToInstall(module);  // modulesToInstall = 追加, 不覆盖 Spring Boot 自动注册的 JSR310 等模块
        };
    }
}
