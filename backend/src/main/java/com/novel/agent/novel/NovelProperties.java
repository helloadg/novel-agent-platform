package com.novel.agent.novel;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 小说写作智能体配置 (前缀 novel)
 * 模型按"档位"复用: 6 个角色映射到 creative / balanced / strict 三档, 避免逐角色重复配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "novel")
public class NovelProperties {

    /** 模型 API Key (与 RAG 复用同一密钥) */
    private String apiKey;

    /** 模型网关 BaseUrl */
    private String baseUrl;

    /** 模型档位: key 为档位名 (creative / balanced / strict) */
    private Map<String, Profile> profile = new LinkedHashMap<>();

    /** 单章目标字数, 写入 prompt 约束 */
    private int chapterTargetWords = 2000;

    /** 单章因审核不通过允许的最大重写轮次 */
    private int chapterMaxRewrite = 2;

    /** 是否启用润色环节 */
    private boolean polishEnabled = true;

    private Context context = new Context();

    /** 按角色取对应档位配置, 档位缺失时回退 balanced */
    public Profile profileOf(AgentRole role) {
        return profileOf(role.getProfile());
    }

    /** 按档位 key 取配置 (角色绑定里可覆盖角色默认档位), 缺失时回退 balanced */
    public Profile profileOf(String profileKey) {
        Profile p = profile.get(profileKey);
        if (p == null) {
            p = profile.get("balanced");
        }
        if (p == null) {
            throw new IllegalStateException(
                    "novel.profile 未配置 (需要的档位: " + profileKey + "), 请在 application.yml 中补充");
        }
        return p;
    }

    /** 单个模型档位参数 */
    @Data
    public static class Profile {
        private String modelName;
        private Double temperature = 0.7;
        private int timeoutSeconds = 120;
        /** 单次调用输出上限, null 表示不传该参数 (兼容网关可能不支持) */
        private Integer maxTokens;
    }

    /** 上下文预算: 控制每章 prompt 的长度 */
    @Data
    public static class Context {
        /** 世界观 (含规则体系) 注入 prompt 的最大字符数, 超出保留首尾 */
        private int worldviewMaxChars = 2000;
        /** 人物设定注入 prompt 的最大字符数, 超出保留开头若干人物 */
        private int charactersMaxChars = 1200;
        /** 最近若干章摘要作为前情 */
        private int recentSummaryCount = 3;
        /** 上一章结尾片段字符数, 保证文风与悬念衔接 */
        private int tailChars = 800;
    }
}
