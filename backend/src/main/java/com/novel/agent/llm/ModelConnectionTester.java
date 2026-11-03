package com.novel.agent.llm;

import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 模型连通性测试
 * <p>
 * 真实发一次极短的 chat 请求, 能拿到模型返回才算连通;
 * 不走 /models 接口, 因为很多网关 models 正常但推理不可用 (或反之)。
 */
@Slf4j
@Component
public class ModelConnectionTester {

    /** 测试用提示词: 尽量短, 减少等待与费用 */
    private static final String PING_PROMPT = "请只回复两个字符: OK";
    /** 连通性测试超时秒数: 推理型模型首包较慢, 留足余量避免误判失败 */
    private static final int TEST_TIMEOUT_SECONDS = 60;

    public TestResult test(String baseUrl, String apiKey, String modelName, Double temperature) {
        long start = System.currentTimeMillis();
        try {
            ChatLanguageModel model = ActiveModelProvider.buildOpenAi(baseUrl, apiKey, modelName,
                    temperature == null ? 0.7 : temperature, TEST_TIMEOUT_SECONDS, null);
            String reply = model.generate(PING_PROMPT);
            long elapsed = System.currentTimeMillis() - start;
            log.info("[model-config] 连通性测试成功 model={} baseUrl={} elapsed={}ms",
                    modelName, baseUrl, elapsed);
            return TestResult.ok(elapsed, reply);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            String message = rootMessage(e);
            log.warn("[model-config] 连通性测试失败 model={} baseUrl={} elapsed={}ms: {}",
                    modelName, baseUrl, elapsed, message);
            return TestResult.fail(elapsed, message);
        }
    }

    /** 取最内层异常信息, 网关的 {"error":{"message":...}} 原文通常在 message 里 */
    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String message = cur.getMessage();
        if (message == null || message.isBlank()) {
            message = e.getMessage();
        }
        if (message == null || message.isBlank()) {
            message = e.getClass().getSimpleName();
        }
        return message;
    }

    /** 测试结果 */
    @Data
    public static class TestResult {
        private boolean success;
        private long elapsedMs;
        private String reply;
        private String error;

        static TestResult ok(long elapsedMs, String reply) {
            TestResult result = new TestResult();
            result.success = true;
            result.elapsedMs = elapsedMs;
            result.reply = reply;
            return result;
        }

        static TestResult fail(long elapsedMs, String error) {
            TestResult result = new TestResult();
            result.success = false;
            result.elapsedMs = elapsedMs;
            result.error = error;
            return result;
        }
    }
}
