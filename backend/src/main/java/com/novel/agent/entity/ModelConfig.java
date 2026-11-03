package com.novel.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 模型配置 (PostgreSQL)
 * <p>
 * 一行对应一个 OpenAI 兼容接入点 (URL + API Key + 模型名)。
 * active=true 的那条是"当前使用"的模型, RAG 问答与小说智能体都会即时切换到它。
 */
@Data
@TableName("t_model_config")
public class ModelConfig {

    /** 自增 ID: 序列化为字符串, 避免前端精度丢失 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 配置名称 (页面展示用, 如 "本地 Gemini 网关") */
    private String name;

    /** OpenAI 兼容 base url, 如 https://xxx/v1 */
    private String baseUrl;

    /** 模型 API Key */
    private String apiKey;

    /** 模型名称, 如 qwen3.8-flash */
    private String modelName;

    /** 采样温度, 为空时按 0.7 */
    private Double temperature;

    /** 单次调用超时秒数, 为空时按 120 */
    private Integer timeoutSeconds;

    /** 单次输出上限, 为空表示不传该参数 */
    private Integer maxTokens;

    /** 是否为当前使用的模型 */
    private Boolean active;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
