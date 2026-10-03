package cn.daxpay.open.paylens.analysis;

import com.openai.core.Timeout;
import com.openai.errors.OpenAIInvalidDataException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import okhttp3.Interceptor;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.template.NoOpTemplateRenderer;

import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

public final class OpenAiCompatibleDiagnosticModel implements DiagnosticModel {
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final String SYSTEM_PROMPT = "你是支付异常分析助手。只能把输入里的证据当作事实；原因必须标为待验证假设。只输出符合要求的 JSON，不执行任何支付或资金操作。";

    private final PayLensAiProperties properties;
    private final MeterRegistry meterRegistry;
    private volatile ChatClient chatClient;

    public OpenAiCompatibleDiagnosticModel(PayLensAiProperties properties) {
        this(properties, new SimpleMeterRegistry());
    }

    public OpenAiCompatibleDiagnosticModel(PayLensAiProperties properties, MeterRegistry meterRegistry) {
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public String analyze(String prompt) throws Exception {
        return analyzeDetailed(prompt).content();
    }

    @Override
    public DiagnosticModelResponse analyzeDetailed(String prompt) {
        long started = System.nanoTime();
        String outcome = "failure";
        try {
            ChatResponse response = chatClient().prompt().user(prompt).call().chatResponse();

            if (response == null || response.getResults() == null || response.getResults().isEmpty()
                    || response.getResult().getOutput() == null || response.getResult().getOutput().getText() == null) {
                throw new DiagnosticModelException("MODEL_INVALID_RESPONSE");
            }

            Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
            String returnedModel = response.getMetadata() == null ? null : response.getMetadata().getModel();
            Long promptTokens = usage == null ? null : asLong(usage.getPromptTokens());
            Long completionTokens = usage == null ? null : asLong(usage.getCompletionTokens());
            recordTokens(promptTokens, completionTokens);
            outcome = "success";
            return new DiagnosticModelResponse(response.getResult().getOutput().getText(), promptTokens,
                    completionTokens, returnedModel == null || returnedModel.isBlank() ? modelId() : returnedModel);
        } catch (RuntimeException exception) {
            throw mapProviderFailure(exception);
        } finally {
            meterRegistry.counter("paylens.ai.calls", "operation", "diagnostic", "outcome", outcome).increment();
            meterRegistry.timer("paylens.ai.duration", "operation", "diagnostic", "outcome", outcome)
                    .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    @Override
    public String modelId() {
        return properties.getModel();
    }

    @Override
    public String configurationId() {
        return properties.getBaseUrl() + "|" + properties.getModel() + "|temperature=0.1|json_object|connect=" +
                properties.getConnectTimeoutSeconds() + "|request=" + properties.getRequestTimeoutSeconds();
    }

    private ChatClient chatClient() {
        ChatClient existing = chatClient;
        if (existing != null) return existing;

        synchronized (this) {
            if (chatClient != null) return chatClient;
            validateConfiguration();

            OpenAiChatOptions options = OpenAiChatOptions.builder()
                    .baseUrl(normalizedBaseUrl())
                    .apiKey(properties.getApiKey())
                    .model(properties.getModel())
                    .temperature(0.1)
                    .timeout(Duration.ofSeconds(properties.getRequestTimeoutSeconds()))
                    .maxRetries(0)
                    .responseFormat(OpenAiChatModel.ResponseFormat.builder()
                            .type(OpenAiChatModel.ResponseFormat.Type.JSON_OBJECT)
                            .build())
                    .build();

            OpenAiChatModel model = OpenAiChatModel.builder()
                    .options(options)
                    .httpClientBuilderCustomizer(builder -> builder
                            .timeout(Timeout.builder()
                                    .connect(Duration.ofSeconds(properties.getConnectTimeoutSeconds()))
                                    .read(Duration.ofSeconds(properties.getRequestTimeoutSeconds()))
                                    .write(Duration.ofSeconds(properties.getRequestTimeoutSeconds()))
                                    .request(Duration.ofSeconds(properties.getRequestTimeoutSeconds()))
                                    .build())
                            .interceptor(responseGuard()))
                    .build();

            chatClient = ChatClient.builder(model)
                    .defaultSystem(SYSTEM_PROMPT)
                    .defaultTemplateRenderer(new NoOpTemplateRenderer())
                    .build();
            return chatClient;
        }
    }

    private void validateConfiguration() {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank() ||
                properties.getModel() == null || properties.getModel().isBlank()) {
            throw new IllegalStateException("AI API key 或模型名未配置");
        }
        if (properties.getBaseUrl() == null || properties.getBaseUrl().isBlank()) {
            throw new IllegalStateException("AI API 地址未配置");
        }
    }

    private String normalizedBaseUrl() {
        return properties.getBaseUrl().replaceAll("/+$", "");
    }

    private Interceptor responseGuard() {
        return chain -> {
            // Spring AI maps OpenAiChatOptions.timeout to an SDK per-request Timeout, which
            // overrides the HTTP client's connect/read/write defaults. Reapply those limits
            // to this OkHttp call while the options timeout bounds the complete request.
            Interceptor.Chain boundedChain = chain
                    .withConnectTimeout(properties.getConnectTimeoutSeconds(), TimeUnit.SECONDS)
                    .withReadTimeout(properties.getRequestTimeoutSeconds(), TimeUnit.SECONDS)
                    .withWriteTimeout(properties.getRequestTimeoutSeconds(), TimeUnit.SECONDS);
            Response response = boundedChain.proceed(boundedChain.request());
            if (!response.isSuccessful()) {
                int status = response.code();
                response.close();
                throw new DiagnosticModelException(status == 429 ? "MODEL_RATE_LIMITED" : "MODEL_HTTP_ERROR");
            }

            ResponseBody body = response.body();
            if (body != null && body.contentLength() > MAX_RESPONSE_BYTES) {
                response.close();
                throw new DiagnosticModelException("MODEL_RESPONSE_TOO_LARGE");
            }
            if (body != null && response.peekBody(MAX_RESPONSE_BYTES + 1L).contentLength() > MAX_RESPONSE_BYTES) {
                response.close();
                throw new DiagnosticModelException("MODEL_RESPONSE_TOO_LARGE");
            }
            return response;
        };
    }

    private RuntimeException mapProviderFailure(RuntimeException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof DiagnosticModelException modelException) return modelException;
            if (cause instanceof InterruptedIOException) return new DiagnosticModelException("MODEL_TIMEOUT");
            if (cause instanceof OpenAIInvalidDataException) return new DiagnosticModelException("MODEL_INVALID_RESPONSE");
        }
        return exception;
    }

    private Long asLong(Integer value) {
        return value == null ? null : value.longValue();
    }

    private void recordTokens(Long promptTokens, Long completionTokens) {
        if (promptTokens != null) meterRegistry.summary("paylens.ai.tokens", "kind", "prompt").record(promptTokens);
        if (completionTokens != null) meterRegistry.summary("paylens.ai.tokens", "kind", "completion").record(completionTokens);
    }
}
