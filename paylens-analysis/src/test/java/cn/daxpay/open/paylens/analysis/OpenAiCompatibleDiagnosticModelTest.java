package cn.daxpay.open.paylens.analysis;

import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiCompatibleDiagnosticModelTest {
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicLong responseHeaderDelayMillis = new AtomicLong();
    private final AtomicLong responseBodyDelayMillis = new AtomicLong();
    private HttpServer server;
    private int responseStatus;
    private String responseBody;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            try {
                if (responseHeaderDelayMillis.get() > 0) Thread.sleep(responseHeaderDelayMillis.get());
                exchange.sendResponseHeaders(responseStatus, body.length);
                if (responseBodyDelayMillis.get() > 0) Thread.sleep(responseBodyDelayMillis.get());
                exchange.getResponseBody().write(body);
            } catch (IOException ignored) {
                // Timeout and size-limit tests deliberately close the client side early.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void sendsCompatibleRequestAndReturnsTextContent() throws Exception {
        responseStatus = 200;
        responseBody = response("test-model", "{\"summary\":\"测试诊断\"}", 7, 3);

        String result = model().analyze("case context {\"must\":\"stay literal\"}");

        assertEquals("{\"summary\":\"测试诊断\"}", result);
        assertEquals("Bearer test-secret", authorization.get());
        var request = mapper.readTree(requestBody.get());
        assertEquals("test-model", request.path("model").asText());
        assertEquals(0.1, request.path("temperature").asDouble());
        assertEquals("json_object", request.path("response_format").path("type").asText());
        assertEquals("case context {\"must\":\"stay literal\"}",
                request.path("messages").path(1).path("content").asText());
        assertTrue(request.path("messages").path(0).path("content").asText().contains("只能把输入里的证据当作事实"));
    }

    @Test
    void detailedResponseCapturesOptionalUsageAndProviderModel() throws Exception {
        responseStatus = 200;
        responseBody = response("provider-model-2026-09", "{\"summary\":\"ok\"}", 123, 45);

        DiagnosticModelResponse result = model().analyzeDetailed("case context");

        assertEquals("{\"summary\":\"ok\"}", result.content());
        assertEquals(123L, result.promptTokens());
        assertEquals(45L, result.completionTokens());
        assertEquals("provider-model-2026-09", result.modelId());
    }

    @Test
    void recordsLowCardinalityCallDurationAndTokenMetrics() throws Exception {
        responseStatus = 200;
        responseBody = response("provider-model-2026-09", "{}", 123, 45);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OpenAiCompatibleDiagnosticModel model = new OpenAiCompatibleDiagnosticModel(properties(), registry);

        model.analyzeDetailed("case context");

        assertEquals(1.0, registry.get("paylens.ai.calls").tag("operation", "diagnostic")
                .tag("outcome", "success").counter().count());
        assertEquals(1L, registry.get("paylens.ai.duration").tag("operation", "diagnostic")
                .tag("outcome", "success").timer().count());
        assertEquals(123.0, registry.get("paylens.ai.tokens").tag("kind", "prompt").summary().totalAmount());
        assertEquals(45.0, registry.get("paylens.ai.tokens").tag("kind", "completion").summary().totalAmount());
    }

    @Test
    void mapsRateLimitToStableFailureCode() {
        responseStatus = 429;
        responseBody = "{\"error\":\"rate limited\"}";

        DiagnosticModelException error = assertThrows(DiagnosticModelException.class,
                () -> model().analyze("context"));

        assertEquals("MODEL_RATE_LIMITED", error.failureCode());
    }

    @Test
    void mapsOtherHttpErrorsToStableFailureCode() {
        responseStatus = 503;
        responseBody = "{\"error\":\"unavailable\"}";

        DiagnosticModelException error = assertThrows(DiagnosticModelException.class,
                () -> model().analyze("context"));

        assertEquals("MODEL_HTTP_ERROR", error.failureCode());
    }

    @Test
    void requestTimeoutAppliesBeforeProviderReturnsHeaders() {
        responseStatus = 200;
        responseBody = responseUnchecked("test-model", "{}", 1, 1);
        responseHeaderDelayMillis.set(1800);

        assertTimeoutFailure();
    }

    @Test
    void requestTimeoutAlsoBoundsProviderBodyRead() {
        responseStatus = 200;
        responseBody = responseUnchecked("test-model", "{}", 1, 1);
        responseBodyDelayMillis.set(1800);

        assertTimeoutFailure();
    }

    @Test
    void mapsMalformedOrUnexpectedProviderResponsesToStableFailureCode() {
        responseStatus = 200;
        responseBody = "not-json";
        DiagnosticModelException malformed = assertThrows(DiagnosticModelException.class,
                () -> model().analyze("context"));
        assertEquals("MODEL_INVALID_RESPONSE", malformed.failureCode());

        responseBody = responseUnchecked("test-model", "{}", 1, 1, List.of());
        DiagnosticModelException missingChoice = assertThrows(DiagnosticModelException.class,
                () -> model().analyze("context"));
        assertEquals("MODEL_INVALID_RESPONSE", missingChoice.failureCode());
    }

    @Test
    void rejectsOversizedProviderResponses() {
        responseStatus = 200;
        responseBody = "x".repeat(64 * 1024 + 1);

        DiagnosticModelException error = assertThrows(DiagnosticModelException.class,
                () -> model().analyze("context"));

        assertEquals("MODEL_RESPONSE_TOO_LARGE", error.failureCode());
    }

    @Test
    void requiresModelConfigurationOnlyWhenARequestIsMade() {
        OpenAiCompatibleDiagnosticModel model = new OpenAiCompatibleDiagnosticModel(new PayLensAiProperties());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> model.analyze("context"));

        assertTrue(error.getMessage().contains("API key"));
    }

    private void assertTimeoutFailure() {
        PayLensAiProperties properties = properties();
        properties.setConnectTimeoutSeconds(2);
        properties.setRequestTimeoutSeconds(1);
        OpenAiCompatibleDiagnosticModel model = new OpenAiCompatibleDiagnosticModel(properties);
        long started = System.nanoTime();

        DiagnosticModelException error = assertThrows(DiagnosticModelException.class,
                () -> model.analyze("context"));

        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
        assertEquals("MODEL_TIMEOUT", error.failureCode());
        assertTrue(elapsedMillis < 3000, "request should stop near its one-second timeout: " + elapsedMillis + "ms");
    }

    private OpenAiCompatibleDiagnosticModel model() {
        return new OpenAiCompatibleDiagnosticModel(properties());
    }

    private PayLensAiProperties properties() {
        PayLensAiProperties properties = new PayLensAiProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/");
        properties.setApiKey("test-secret");
        properties.setModel("test-model");
        properties.setConnectTimeoutSeconds(2);
        properties.setRequestTimeoutSeconds(2);
        return properties;
    }

    private String response(String model, String content, int promptTokens, int completionTokens) throws Exception {
        return response(model, content, promptTokens, completionTokens, List.of(
                Map.of("index", 0, "message", Map.of("role", "assistant", "content", content), "finish_reason", "stop")));
    }

    private String response(String model, String content, int promptTokens, int completionTokens,
                            List<Map<String, Object>> choices) throws Exception {
        return mapper.writeValueAsString(Map.of(
                "id", "chatcmpl-test",
                "object", "chat.completion",
                "created", 1_758_000_000,
                "model", model,
                "choices", choices,
                "usage", Map.of("prompt_tokens", promptTokens,
                        "completion_tokens", completionTokens,
                        "total_tokens", promptTokens + completionTokens)));
    }

    private String responseUnchecked(String model, String content, int promptTokens, int completionTokens) {
        try { return response(model, content, promptTokens, completionTokens); }
        catch (Exception e) { throw new AssertionError(e); }
    }

    private String responseUnchecked(String model, String content, int promptTokens, int completionTokens,
                                     List<Map<String, Object>> choices) {
        try { return response(model, content, promptTokens, completionTokens, choices); }
        catch (Exception e) { throw new AssertionError(e); }
    }
}
