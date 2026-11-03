package com.novel.agent.llm;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 动态模型代理
 * <p>
 * 注册为 @Primary 的 ChatLanguageModel Bean, 所有按类型注入的调用方 (RAG / QueryRewrite / Assistant)
 * 实际拿到的都是这个代理; 每次调用时才向 {@link ActiveModelProvider} 取当前生效的模型,
 * 因此页面切换模型后立即生效, 无需重启, 也不需要重建 AiServices。
 */
@RequiredArgsConstructor
public class DynamicChatLanguageModel implements ChatLanguageModel {

    private final ActiveModelProvider provider;

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages) {
        return provider.current().generate(messages);
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages, List<ToolSpecification> toolSpecifications) {
        return provider.current().generate(messages, toolSpecifications);
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages, ToolSpecification toolSpecification) {
        return provider.current().generate(messages, toolSpecification);
    }
}
