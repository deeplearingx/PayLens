package cn.daxpay.open.paylens.demo;

import cn.daxpay.open.paylens.analysis.BgeSmallZhOnnxEmbeddingModel;
import cn.daxpay.open.paylens.incidents.domain.Observation;
import cn.daxpay.open.paylens.incidents.domain.SimilarityProvider;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

@Component
@ConditionalOnProperty(prefix = "paylens.incidents", name = "enabled", havingValue = "true")
public class PayLensIncidentSimilarityProvider implements SimilarityProvider {
    private static final String PREPROCESS_VERSION = "bge-small-zh-v1.5-cls-l2-v1";
    private final ObjectProvider<BgeSmallZhOnnxEmbeddingModel> modelProvider;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ThreadPoolTaskExecutor inferenceExecutor;
    private final ThreadLocal<Map<String, float[]>> preparedVectors = ThreadLocal.withInitial(HashMap::new);

    public PayLensIncidentSimilarityProvider(ObjectProvider<BgeSmallZhOnnxEmbeddingModel> modelProvider,
                                             JdbcTemplate jdbc, ObjectMapper mapper,
                                             @Qualifier("payLensEmbeddingExecutor") ObjectProvider<ThreadPoolTaskExecutor> executorProvider) {
        this.modelProvider = modelProvider;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.inferenceExecutor = executorProvider.getIfAvailable();
    }

    public boolean semanticReady() {
        BgeSmallZhOnnxEmbeddingModel model = modelProvider.getIfAvailable();
        return model != null && model.isReady();
    }

    public Map<String, Object> modelMetadata() {
        BgeSmallZhOnnxEmbeddingModel model = modelProvider.getIfAvailable();
        if (model == null || !model.isReady()) return Map.of("model", "UNAVAILABLE");
        return Map.of("model", "BAAI/bge-small-zh-v1.5", "revision", model.modelRevision(),
                "modelFileSha256", model.modelSha256(), "preprocessing", PREPROCESS_VERSION);
    }

    /** Loads/caches each observation vector once, in bounded model batches, before pair comparisons begin. */
    public void prepare(List<Observation> observations) {
        BgeSmallZhOnnxEmbeddingModel model = modelProvider.getIfAvailable();
        if (model == null || !model.isReady()) throw new IllegalStateException("LOCAL_EMBEDDING_NOT_READY");
        String modelHash = model.modelSha256();
        Map<String, String> textByKey = new LinkedHashMap<>();
        for (Observation observation : observations) {
            String text = normalize(observation.normalizedDescription());
            textByKey.putIfAbsent(cacheKey(text, modelHash), text);
        }
        Map<String, float[]> resolved = new HashMap<>();
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, String> item : textByKey.entrySet()) {
            float[] cached = readCache(item.getValue(), modelHash);
            if (cached == null) missing.add(item.getValue());
            else resolved.put(item.getKey(), cached);
        }
        if (!missing.isEmpty()) {
            if (inferenceExecutor == null) throw new IllegalStateException("LOCAL_EMBEDDING_EXECUTOR_MISSING");
            Future<Map<String, float[]>> future;
            try {
                future = inferenceExecutor.submit(() -> embedAndCache(model, modelHash, missing));
            } catch (TaskRejectedException exception) {
                throw new IllegalStateException("LOCAL_EMBEDDING_EXECUTOR_BUSY", exception);
            }
            try {
                resolved.putAll(future.get());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("LOCAL_EMBEDDING_INTERRUPTED", exception);
            } catch (ExecutionException exception) {
                Throwable cause = exception.getCause();
                if (cause instanceof RuntimeException runtimeException) throw runtimeException;
                throw new IllegalStateException("LOCAL_EMBEDDING_INFERENCE_FAILED", cause);
            }
        }
        preparedVectors.get().putAll(resolved);
    }

    public void clearPrepared() {
        preparedVectors.remove();
    }

    @Override
    public double similarity(Observation left, Observation right) {
        BgeSmallZhOnnxEmbeddingModel model = modelProvider.getIfAvailable();
        if (model == null || !model.isReady()) {
            String first = normalize(left.normalizedDescription());
            String second = normalize(right.normalizedDescription());
            return first.equals(second) ? 1.0 : 0.0;
        }
        float[] first = preparedVector(model, left.normalizedDescription());
        float[] second = preparedVector(model, right.normalizedDescription());
        if (first.length != second.length) throw new IllegalStateException("LOCAL_EMBEDDING_DIMENSION_MISMATCH");
        double score = 0;
        for (int index = 0; index < first.length; index++) score += (double) first[index] * second[index];
        return Math.max(-1.0, Math.min(1.0, score));
    }

    private float[] preparedVector(BgeSmallZhOnnxEmbeddingModel model, String rawText) {
        String text = normalize(rawText);
        String modelHash = model.modelSha256();
        String key = cacheKey(text, modelHash);
        float[] prepared = preparedVectors.get().get(key);
        if (prepared != null) return prepared;
        float[] cached = readCache(text, modelHash);
        if (cached != null) return cached;
        return embedAndCache(model, modelHash, List.of(text)).get(key);
    }

    private float[] readCache(String text, String modelHash) {
        String textHash = sha256(text);
        List<float[]> cached = jdbc.query("""
                SELECT vector_json FROM paylens_embedding_cache
                 WHERE text_sha256=? AND model_sha256=? AND preprocess_version=?
                """, (rs, row) -> {
            try { return mapper.readValue(rs.getString(1), float[].class); }
            catch (JacksonException exception) { throw new IllegalStateException("向量缓存无法读取", exception); }
        }, textHash, modelHash, PREPROCESS_VERSION);
        if (!cached.isEmpty()) {
            float[] vector = cached.getFirst();
            if (vector.length != 512) throw new IllegalStateException("LOCAL_EMBEDDING_DIMENSION_MISMATCH");
            return vector;
        }
        return null;
    }

    private Map<String, float[]> embedAndCache(BgeSmallZhOnnxEmbeddingModel model, String modelHash, List<String> texts) {
        Map<String, float[]> result = new LinkedHashMap<>();
        int batchSize = model.maxBatchSize();
        for (int start = 0; start < texts.size(); start += batchSize) {
            List<String> batch = texts.subList(start, Math.min(texts.size(), start + batchSize));
            List<Embedding> embeddings = model.call(new EmbeddingRequest(batch, null)).getResults();
            if (embeddings.size() != batch.size()) throw new IllegalStateException("LOCAL_EMBEDDING_BATCH_MISMATCH");
            for (int index = 0; index < batch.size(); index++) {
                String text = batch.get(index);
                float[] vector = embeddings.get(index).getOutput();
                if (vector.length != 512) throw new IllegalStateException("LOCAL_EMBEDDING_DIMENSION_MISMATCH");
                saveCache(text, modelHash, vector);
                result.put(cacheKey(text, modelHash), vector);
            }
        }
        return result;
    }

    private void saveCache(String text, String modelHash, float[] vector) {
        try {
            jdbc.update("""
                    INSERT INTO paylens_embedding_cache(text_sha256,model_sha256,preprocess_version,dimensions,vector_json)
                    VALUES (?,?,?,?,?::jsonb) ON CONFLICT (text_sha256,model_sha256,preprocess_version) DO NOTHING
                    """, sha256(text), modelHash, PREPROCESS_VERSION, vector.length, mapper.writeValueAsString(vector));
        } catch (JacksonException exception) {
            throw new IllegalStateException("向量无法保存", exception);
        }
    }

    private String cacheKey(String text, String modelHash) { return modelHash + ":" + sha256(text) + ":" + PREPROCESS_VERSION; }

    private String normalize(String value) {
        if (value == null || value.isBlank()) return "未知的支付异常现象";
        return value.trim().replaceAll("\\s+", " ");
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM 不支持 SHA-256", exception);
        }
    }
}
