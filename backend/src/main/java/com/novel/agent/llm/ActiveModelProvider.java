package com.novel.agent.llm;

import com.novel.agent.entity.ModelConfig;
import com.novel.agent.observability.langfuse.LangfuseClient;
import com.novel.agent.observability.langfuse.TracingChatLanguageModel;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 当前生效模型的解析与缓存
 * <p>
 * 优先级: 数据库里 active=true 的模型配置 > application.yml (langchain4j.open-ai.chat-model)。
 * 配置变更 (新增/修改/启用/删除) 后调用 {@link #invalidate()}, 下一次取模型时自动重建,
 * 无需重启应用; {@link #version()} 供各智能体感知变更并清理自己的缓存。
 * <p>
 * 首次访问时才查库: 建表由启动后的 ApplicationRunner 执行, 构造期查库会拿到"表不存在"。
 * <p>
 * 返回的模型会自动用 {@link TracingChatLanguageModel} 包装 (如果 Langfuse 已启用),
 * 对调用方透明, 所有 RAG/Assistant 的 LLM 调用都会自动带 trace。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ActiveModelProvider {

    private final ModelConfigService configService;
    private final Environment environment;
    private final LangfuseClient langfuseClient;

    /** 配置版本号, 每次变更自增; 作为各级缓存的失效标记 */
    private final AtomicLong version = new AtomicLong();

    private final Object lock = new Object();

    private volatile ModelConfig cachedActive;
    private volatile long cachedConfigVersion = -1;

    private volatile ChatLanguageModel cachedModel;
    private volatile long cachedModelVersion = -1;

    public long version() {
        return version.get();
    }

    /** 主动失效: 配置变更后调用 */
    public void invalidate() {
        version.incrementAndGet();
    }

    /** 数据库里当前启用的配置 (不存在时为空, 调用方回退 yml) */
    public Optional<ModelConfig> activeConfig() {
        long v = version.get();
        if (v != cachedConfigVersion) {
            synchronized (lock) {
                if (v != cachedConfigVersion) {
                    cachedActive = loadActive();
                    cachedConfigVersion = v;
                }
            }
        }
        return Optional.ofNullable(cachedActive);
    }

    /** RAG / Assistant 使用的模型: 有启用配置用它, 否则用 yml 的 chat-model */
    public ChatLanguageModel current() {
        long v = version.get();
        ChatLanguageModel model = cachedModel;
        if (model != null && cachedModelVersion == v) {
            return model;
        }
        synchronized (lock) {
            if (cachedModel == null || cachedModelVersion != version.get()) {
                cachedModel = buildCurrent();
                cachedModelVersion = version.get();
            }
            return cachedModel;
        }
    }

    private ModelConfig loadActive() {
        try {
            return configService.getActive();
        } catch (Exception e) {
            log.warn("[model-config] 读取启用配置失败, 暂用 yml 配置: {}", e.getMessage());
            return null;
        }
    }

    private ChatLanguageModel buildCurrent() {
        String modelName;
        ChatLanguageModel raw;
        Optional<ModelConfig> opt = activeConfig();
        if (opt.isPresent()) {
            ModelConfig config = opt.get();
            modelName = config.getModelName();
            log.info("[model-config] 使用页面启用配置: name={} model={} baseUrl={}",
                    config.getName(), config.getModelName(), config.getBaseUrl());
            raw = buildOpenAi(config.getBaseUrl(), config.getApiKey(), config.getModelName(),
                    config.getTemperature() == null ? 0.7 : config.getTemperature(),
                    config.getTimeoutSeconds() == null ? 120 : config.getTimeoutSeconds(),
                    config.getMaxTokens());
        } else {
            String apiKey = environment.getProperty("langchain4j.open-ai.chat-model.api-key", "");
            String baseUrl = environment.getProperty("langchain4j.open-ai.chat-model.base-url",
                    "https://api.openai.com/v1");
            modelName = environment.getProperty("langchain4j.open-ai.chat-model.model-name",
                    "gpt-4o-mini");
            Double temperature = environment.getProperty("langchain4j.open-ai.chat-model.temperature",
                    Double.class, 0.7);
            Duration timeout = environment.getProperty("langchain4j.open-ai.chat-model.timeout",
                    Duration.class, Duration.ofSeconds(30));
            Integer maxTokens = environment.getProperty("langchain4j.open-ai.chat-model.max-tokens",
                    Integer.class);
            log.info("[model-config] 未启用页面配置, 回退 yml: model={} baseUrl={}", modelName, baseUrl);
            raw = buildOpenAi(baseUrl, apiKey, modelName, temperature,
                    (int) timeout.toSeconds(), maxTokens);
        }
        // Langfuse 包装 (如果已启用)
        if (langfuseClient.isEnabled()) {
            Map<String, String> meta = new LinkedHashMap<>();
            meta.put("source", "rag");
            meta.put("model", modelName);
            return new TracingChatLanguageModel(raw, langfuseClient, modelName, meta);
        }
        return raw;
    }

    static ChatLanguageModel buildOpenAi(String baseUrl, String apiKey, String modelName,
                                         Double temperature, int timeoutSeconds, Integer maxTokens) {
        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(temperature)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .maxRetries(1);
        // 部分兼容网关不支持 maxTokens 参数, 未配置时不传
        if (maxTokens != null && maxTokens > 0) {
            builder.maxTokens(maxTokens);
        }
        return builder.build();
    }
}
