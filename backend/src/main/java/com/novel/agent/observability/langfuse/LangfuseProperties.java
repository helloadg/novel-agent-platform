package com.novel.agent.observability.langfuse;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Langfuse 可观测性配置
 * <p>
 * 自部署 Langfuse v4, 走 legacy batch ingestion API (POST /api/public/ingestion)
 * 通过 LANGFUSE_MIGRATION_V4_WRITE_MODE=dual 同时兼容 trace/generation/span 事件。
 */
@Data
@ConfigurationProperties(prefix = "langfuse")
public class LangfuseProperties {

    /** 是否启用 Langfuse 可观测性, 默认 false (安全开关) */
    private boolean enabled = false;

    /** Langfuse 基座地址, 默认 http://localhost:3001 */
    private String url = "http://localhost:3001";

    /** Langfuse project public key (Bearer/Basic Auth 用户名) */
    private String publicKey = "";

    /** Langfuse project secret key (Basic Auth 密码) */
    private String secretKey = "";

    /** 每次 batch 发送的最大事件数, 超过时分批 */
    private int batchSize = 32;

    /** HTTP 超时, 默认 5s (可观测性不能拖慢主请求) */
    private int timeoutMs = 5000;
}
