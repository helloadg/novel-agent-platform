package com.novel.agent.observability.langfuse;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Langfuse legacy batch ingestion HTTP 客户端
 * <p>
 * 用 JDK 11 HttpClient 直连 /api/public/ingestion, 异步 fire-and-forget,
 * 可观测性失败不影响主业务流程。鉴权: Basic base64(publicKey:secretKey)。
 */
@Slf4j
@Component
public class LangfuseClient {

    private final LangfuseProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final String basicAuthHeader;

    public LangfuseClient(LangfuseProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        // 强制 HTTP/1.1, 避免 Next.js 服务端对 HTTP/2 preface 不响应导致 "header parser received no bytes"
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getTimeoutMs()))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        String credentials = props.getPublicKey() + ":" + props.getSecretKey();
        this.basicAuthHeader = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes());
    }

    public boolean isEnabled() {
        return props.isEnabled()
                && props.getPublicKey() != null && !props.getPublicKey().isBlank()
                && props.getSecretKey() != null && !props.getSecretKey().isBlank();
    }

    /**
     * 异步发送一批事件到 Langfuse
     *
     * @param events 每条事件是一个 map, 结构: {type, id, timestamp, body}
     */
    @Async("langfuseTaskExecutor")
    public void sendBatch(List<Map<String, Object>> events) {
        if (!isEnabled() || events == null || events.isEmpty()) {
            return;
        }
        try {
            Map<String, Object> payload = Map.of("batch", events);
            byte[] body = mapper.writeValueAsBytes(payload);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(props.getUrl().trim().replaceAll("/+$", "") + "/api/public/ingestion"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", basicAuthHeader)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .timeout(Duration.ofMillis(props.getTimeoutMs()))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                log.warn("[langfuse] ingestion returned HTTP {}: {}", response.statusCode(), response.body());
            }
        } catch (Exception e) {
            log.warn("[langfuse] ingestion failed: {}", e.getMessage());
        }
    }
}
