package com.novel.agent.novel;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.novel.agent.entity.ModelConfig;
import com.novel.agent.entity.ModelRoleBinding;
import com.novel.agent.llm.ActiveModelProvider;
import com.novel.agent.llm.ModelConfigService;
import com.novel.agent.mapper.ModelRoleBindingMapper;
import com.novel.agent.observability.langfuse.LangfuseClient;
import com.novel.agent.observability.langfuse.TracingChatLanguageModel;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 小说智能体模型工厂
 * <p>
 * 关键设计: 这里构建的是**普通对象, 不注册为 Spring Bean**。
 * 原因: 现有 RAG 链路通过按类型 (ChatLanguageModel) 注入模型, 若再注册一个同类型 Bean
 * 会导致 NoUniqueBeanDefinitionException, 使 RAG 启动失败。
 * 因此本工厂不参与 Spring 的类型解析, 各智能体注入本类 (唯一类型) 后按角色取模型。
 * <p>
 * 返回的模型会自动用 {@link TracingChatLanguageModel} 包装 (如果 Langfuse 已启用),
 * metadata 里带 role 区分不同小说智能体。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NovelModelFactory {

    private final NovelProperties properties;
    private final ActiveModelProvider modelProvider;
    private final ModelConfigService configService;
    private final ModelRoleBindingMapper bindingMapper;
    private final LangfuseClient langfuseClient;

    /** 按角色缓存模型实例, 避免每个节点重复构建 */
    private final Map<String, ChatLanguageModel> cache = new ConcurrentHashMap<>();

    /** 缓存对应的模型配置版本, 页面切换模型后自动失效重建 */
    private volatile long cacheVersion = -1;

    public ChatLanguageModel of(AgentRole role) {
        long version = modelProvider.version();
        if (version != cacheVersion) {
            synchronized (cache) {
                if (version != cacheVersion) {
                    cache.clear();
                    cacheVersion = version;
                }
            }
        }
        return cache.computeIfAbsent(role.name(), key -> build(role));
    }

    private ChatLanguageModel build(AgentRole role) {
        // 接入点 (URL/Key/模型名) 优先级: 该角色绑定的配置 > 全局 active 配置 > novel.* 的 yml 配置。
        // 温度/超时/输出上限按档位取, 绑定行里选的档位可覆盖角色默认档位。
        ModelRoleBinding binding = findBinding(role);
        String profileKey = binding != null && !isBlank(binding.getProfile())
                ? binding.getProfile() : role.getProfile();
        NovelProperties.Profile profile = properties.profileOf(profileKey);

        ModelConfig endpoint = binding == null ? null : configService.get(binding.getModelConfigId());
        String source = "binding";
        if (endpoint == null) {
            endpoint = modelProvider.activeConfig().orElse(null);
            source = endpoint == null ? "yml" : "active";
        }

        String apiKey = endpoint == null ? properties.getApiKey() : endpoint.getApiKey();
        String baseUrl = endpoint == null ? properties.getBaseUrl() : endpoint.getBaseUrl();
        String modelName = endpoint == null ? profile.getModelName() : endpoint.getModelName();
        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .temperature(profile.getTemperature())
                .timeout(Duration.ofSeconds(profile.getTimeoutSeconds()))
                .maxRetries(1);
        // 部分兼容网关不支持 maxTokens 参数, 未配置时不传
        if (profile.getMaxTokens() != null) {
            builder.maxTokens(profile.getMaxTokens());
        }
        ChatLanguageModel model = builder.build();
        log.info("[novel] 构建模型 role={} source={} profile={} model={} temperature={} timeout={}s",
                role.name(), source, profileKey, modelName,
                profile.getTemperature(), profile.getTimeoutSeconds());
        // Langfuse 包装 (如果已启用), metadata 里透传 role 区分不同智能体
        if (langfuseClient.isEnabled()) {
            Map<String, String> meta = new LinkedHashMap<>();
            meta.put("source", "novel");
            meta.put("role", role.name());
            meta.put("profile", profileKey);
            return new TracingChatLanguageModel(model, langfuseClient, modelName, meta);
        }
        return model;
    }

    /** 查该角色的模型绑定; 表不可用或查询异常时返回 null (回退全局 active / yml) */
    private ModelRoleBinding findBinding(AgentRole role) {
        try {
            return bindingMapper.selectOne(new LambdaQueryWrapper<ModelRoleBinding>()
                    .eq(ModelRoleBinding::getAgentRole, role.getBindingKey()));
        } catch (Exception e) {
            log.warn("[novel] 读取角色绑定失败, 回退全局 active: role={} error={}",
                    role.getBindingKey(), e.getMessage());
            return null;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
