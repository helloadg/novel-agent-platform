package com.novel.agent.novel;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 结构化输出解析: 从 LLM 返回文本中抽取 JSON
 * LLM 常把 JSON 包在 ```json 代码块里, 或在前后附带解释性文字, 这里统一容错
 */
public final class StructuredOutputParser {

    /** 宽松反序列化用的 ObjectMapper: 忽略模型多给的未知字段 */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** 匹配 ```json ... ``` 或 ``` ... ``` 代码块 */
    private static final Pattern CODE_BLOCK = Pattern.compile("(?s)```(?:json)?\\s*(.*?)```");

    private StructuredOutputParser() {
    }

    /** 解析 JSON 为对象, 无法解析时返回 empty (由调用方降级处理) */
    public static <T> Optional<T> parse(String raw, Class<T> type) {
        String json = extractJson(raw);
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(MAPPER.readValue(json, type));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** 对象序列化为 JSON 文本 (用于落 PG 或写入图状态) */
    public static String toJson(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** 抽取首个 '{' 到末个 '}' 之间的内容 */
    static String extractJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        Matcher matcher = CODE_BLOCK.matcher(s);
        if (matcher.find()) {
            s = matcher.group(1).trim();
        }
        int left = s.indexOf('{');
        int right = s.lastIndexOf('}');
        if (left < 0 || right <= left) {
            return null;
        }
        return s.substring(left, right + 1);
    }
}
