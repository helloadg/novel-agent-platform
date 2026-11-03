package com.novel.agent.observability.langfuse;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 带 Langfuse trace 的 ChatLanguageModel 装饰器
 * <p>
 * 每次 generate() 调用前创建 trace-create 事件, 调用原模型, 调用后创建 generation-create 事件,
 * 两者一起批量 POST 到 Langfuse /api/public/ingestion。所有事件异步发送 (fire-and-forget),
 * 不阻塞主链路。
 * <p>
 * 一次 generate 调用 = 一个 trace + 一个 generation。trace 是整个请求根 span, generation 是具体的 LLM 调用。
 */
@Slf4j
public class TracingChatLanguageModel implements ChatLanguageModel {

    private final ChatLanguageModel delegate;
    private final LangfuseClient langfuse;
    /** 模型名称 (用于 generation body 的 model 字段) */
    private final String modelName;
    /** 附加 metadata, 比如 {"source": "rag", "role": "chapter_writer"} */
    private final Map<String, String> metadata;

    public TracingChatLanguageModel(ChatLanguageModel delegate,
                                    LangfuseClient langfuse,
                                    String modelName,
                                    Map<String, String> metadata) {
        this.delegate = delegate;
        this.langfuse = langfuse;
        this.modelName = modelName == null ? delegate.getClass().getSimpleName() : modelName;
        this.metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages) {
        return generateInternal(messages, null);
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages, List<ToolSpecification> toolSpecifications) {
        return generateInternal(messages, toolSpecifications);
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages, ToolSpecification toolSpecification) {
        List<ToolSpecification> single = toolSpecification == null ? null : List.of(toolSpecification);
        return generateInternal(messages, single);
    }

    private Response<AiMessage> generateInternal(List<ChatMessage> messages, List<ToolSpecification> tools) {
        if (!langfuse.isEnabled()) {
            return delegate.generate(messages);
        }
        String traceId = UUID.randomUUID().toString();
        String generationId = UUID.randomUUID().toString();
        String traceTimestamp = Instant.now().toString();

        // 构造 input (messages -> 简单文本表示, 避免过长)
        String inputPreview = buildInputPreview(messages);

        // trace body
        Map<String, Object> traceBody = new LinkedHashMap<>();
        traceBody.put("id", traceId);
        traceBody.put("name", "LLM Chat");
        traceBody.put("input", inputPreview);
        Map<String, Object> traceMeta = new LinkedHashMap<>(metadata);
        traceMeta.put("model", modelName);
        if (tools != null && !tools.isEmpty()) {
            traceMeta.put("tools", String.valueOf(tools.size()));
        }
        traceBody.put("metadata", traceMeta);

        // generation body (先填 startTime, output/endTime 调完再补)
        String startTime = Instant.now().toString();
        Map<String, Object> genBody = new LinkedHashMap<>();
        genBody.put("id", generationId);
        genBody.put("traceId", traceId);
        genBody.put("name", "LLM Generation");
        genBody.put("model", modelName);
        genBody.put("input", inputPreview);
        genBody.put("startTime", startTime);

        // 先发 trace-create (异步)
        langfuse.sendBatch(List.of(event("trace-create", "evt-" + traceId, traceTimestamp, traceBody)));

        Response<AiMessage> response;
        try {
            response = delegate.generate(messages);
        } catch (RuntimeException e) {
            // LLM 调用失败也要把 generation 事件发出去 (带 error 状态)
            genBody.put("output", null);
            genBody.put("endTime", Instant.now().toString());
            genBody.put("statusMessage", e.getMessage());
            genBody.put("level", "ERROR");
            langfuse.sendBatch(List.of(event("generation-create", "evt-" + generationId, Instant.now().toString(), genBody)));
            throw e;
        }

        String endTime = Instant.now().toString();
        AiMessage ai = response.content();
        genBody.put("output", ai.text());
        genBody.put("endTime", endTime);
        // usage (langchain4j 的 Response 里有 TokenUsage)
        if (response.tokenUsage() != null) {
            Map<String, Object> usage = new LinkedHashMap<>();
            if (response.tokenUsage().inputTokenCount() != null) {
                usage.put("input", response.tokenUsage().inputTokenCount());
            }
            if (response.tokenUsage().outputTokenCount() != null) {
                usage.put("output", response.tokenUsage().outputTokenCount());
            }
            if (response.tokenUsage().totalTokenCount() != null) {
                usage.put("total", response.tokenUsage().totalTokenCount());
            }
            if (!usage.isEmpty()) {
                genBody.put("usage", usage);
            }
        }

        // 发 generation-create
        langfuse.sendBatch(List.of(event("generation-create", "evt-" + generationId, Instant.now().toString(), genBody)));

        return response;
    }

    private static Map<String, Object> event(String type, String id, String timestamp, Map<String, Object> body) {
        Map<String, Object> evt = new LinkedHashMap<>();
        evt.put("type", type);
        evt.put("id", id);
        evt.put("timestamp", timestamp);
        evt.put("body", body);
        return evt;
    }

    /** 把 ChatMessage 列表压成一段简短文本, 避免 Langfuse ingestion body 过大 */
    private static String buildInputPreview(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int limit = 2000;
        for (ChatMessage m : messages) {
            String text = textOf(m);
            if (sb.length() + text.length() > limit) {
                sb.append(text, 0, Math.max(0, limit - sb.length())).append("...(truncated)");
                break;
            }
            sb.append(text).append("\n");
        }
        return sb.toString().trim();
    }

    private static String textOf(ChatMessage m) {
        String label;
        switch (m.type()) {
            case SYSTEM -> label = "[System]";
            case USER -> label = "[User]";
            case AI -> label = "[AI]";
            case TOOL_EXECUTION_RESULT -> label = "[Tool]";
            default -> label = "[" + m.type().name() + "]";
        }
        return label + " " + textSafe(m);
    }

    private static String textSafe(ChatMessage m) {
        try {
            return m.text();
        } catch (Exception e) {
            return m.toString();
        }
    }
}
