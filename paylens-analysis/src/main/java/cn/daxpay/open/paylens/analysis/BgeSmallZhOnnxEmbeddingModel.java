package cn.daxpay.open.paylens.analysis;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.AbstractEmbeddingModel;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CPU-only BGE adapter. Spring AI's stock TransformersEmbeddingModel mean-pools token
 * outputs; BGE's model card specifies CLS pooling followed by L2 normalization.
 */
public final class BgeSmallZhOnnxEmbeddingModel extends AbstractEmbeddingModel implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(BgeSmallZhOnnxEmbeddingModel.class);
    private static final String MODEL_ID = "BAAI/bge-small-zh-v1.5";
    private static final String MODEL_REVISION = "7999e1d3359715c523056ef9478215996d62a620";
    private static final int VECTOR_DIMENSIONS = 512;
    private final PayLensEmbeddingProperties properties;
    private final ThreadPoolTaskExecutor initializer;
    private final AtomicBoolean closeRequested = new AtomicBoolean();
    private volatile OrtEnvironment environment;
    private volatile HuggingFaceTokenizer tokenizer;
    private volatile OrtSession session;
    private volatile Set<String> inputNames = Set.of();
    private volatile String modelSha256;
    private volatile String modelRevision;
    private volatile String failureCode;

    public BgeSmallZhOnnxEmbeddingModel(PayLensEmbeddingProperties properties,
                                       ThreadPoolTaskExecutor initializer) {
        this.properties = properties;
        this.initializer = initializer;
        this.embeddingDimensions.set(VECTOR_DIMENSIONS);
    }

    public void initializeAsync() {
        initializer.execute(() -> {
            try {
                initializeLocalFiles();
            } catch (Exception exception) {
                failureCode = classify(exception);
                log.warn("PayLens 本地语义模型未就绪，当前批次将只执行规则归并（{}）", failureCode);
            }
        });
    }

    /** Package-visible for deterministic missing-file and manifest tests. */
    void initializeLocalFiles() throws IOException, OrtException {
        Path directory = Path.of(properties.getModelDirectory()).toAbsolutePath().normalize();
        Path modelPath = directory.resolve("model.onnx");
        Path tokenizerPath = directory.resolve("tokenizer.json");
        Path manifestPath = directory.resolve("model-manifest.properties");
        if (!Files.isRegularFile(modelPath) || !Files.isRegularFile(tokenizerPath) ||
                !Files.isRegularFile(manifestPath)) {
            throw new IOException("MODEL_FILES_MISSING");
        }
        Properties manifest = new Properties();
        try (InputStream input = Files.newInputStream(manifestPath)) { manifest.load(input); }
        if (!MODEL_ID.equals(manifest.getProperty("model"))) throw new IOException("MODEL_ID_MISMATCH");
        String expectedRevision = manifest.getProperty("revision");
        if (!MODEL_REVISION.equals(expectedRevision)) throw new IOException("MODEL_REVISION_MISMATCH");
        if (!"CLS".equals(manifest.getProperty("pooling")) || !"L2".equals(manifest.getProperty("normalization"))
                || !Integer.toString(VECTOR_DIMENSIONS).equals(manifest.getProperty("embedding.dimensions"))
                || !"last_hidden_state".equals(manifest.getProperty("export.onnx_output"))
                || !"true".equalsIgnoreCase(manifest.getProperty("reference.pairwise-ranking-validated"))) {
            throw new IOException("MODEL_PREPROCESSING_UNVERIFIED");
        }
        modelSha256 = sha256(modelPath);
        if (!modelSha256.equalsIgnoreCase(manifest.getProperty("model.onnx.sha256", ""))) {
            throw new IOException("MODEL_HASH_MISMATCH");
        }
        if (!sha256(tokenizerPath).equalsIgnoreCase(manifest.getProperty("tokenizer.json.sha256", ""))) {
            throw new IOException("TOKENIZER_HASH_MISMATCH");
        }
        modelRevision = expectedRevision;

        OrtEnvironment loadedEnvironment = OrtEnvironment.getEnvironment();

        HuggingFaceTokenizer loadedTokenizer;
        try (InputStream input = Files.newInputStream(tokenizerPath)) {
            loadedTokenizer = HuggingFaceTokenizer.newInstance(input, Map.of(
                    "padding", "true", "truncation", "true",
                    "maxLength", Integer.toString(properties.getMaxSequenceLength())));
        }
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setIntraOpNumThreads(2);
        options.setInterOpNumThreads(1);
        options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
        OrtSession loadedSession;
        try (options) {
            loadedSession = loadedEnvironment.createSession(modelPath.toString(), options);
        } catch (Exception exception) {
            loadedTokenizer.close();
            throw exception;
        }
        if (!loadedSession.getOutputNames().contains("last_hidden_state")) {
            loadedSession.close();
            loadedTokenizer.close();
            throw new IOException("MODEL_OUTPUT_MISSING");
        }
        if (closeRequested.get()) {
            loadedSession.close();
            loadedTokenizer.close();
            return;
        }
        tokenizer = loadedTokenizer;
        session = loadedSession;
        environment = loadedEnvironment;
        inputNames = Set.copyOf(loadedSession.getInputNames());
        failureCode = null;
        log.info("PayLens BGE 本地模型已就绪：revision={}，维度={}，执行设备=CPU", modelRevision, VECTOR_DIMENSIONS);
    }

    public boolean isReady() { return session != null && tokenizer != null && !closeRequested.get(); }
    public String modelSha256() { return modelSha256; }
    public String modelRevision() { return modelRevision; }
    public String failureCode() { return failureCode; }
    public int maxBatchSize() { return properties.getMaxBatchSize(); }

    @Override
    public float[] embed(Document document) {
        if (document == null) throw new IllegalArgumentException("Embedding 文档不能为空");
        return embed(document.getText());
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        if (!isReady()) throw new IllegalStateException("LOCAL_EMBEDDING_NOT_READY");
        List<String> texts = request.getInstructions();
        if (texts == null || texts.isEmpty()) throw new IllegalArgumentException("Embedding 文本不能为空");
        if (texts.size() > properties.getMaxBatchSize()) throw new IllegalArgumentException("Embedding 批次超过上限");
        if (texts.stream().anyMatch(text -> text == null || text.isBlank())) {
            throw new IllegalArgumentException("Embedding 文本不能为空");
        }
        try {
            if (environment == null) throw new IllegalStateException("LOCAL_EMBEDDING_NOT_READY");
            Encoding[] encodings = tokenizer.batchEncode(texts.toArray(String[]::new));
            long[][] ids = new long[encodings.length][];
            long[][] masks = new long[encodings.length][];
            long[][] typeIds = new long[encodings.length][];
            for (int i = 0; i < encodings.length; i++) {
                ids[i] = encodings[i].getIds();
                masks[i] = encodings[i].getAttentionMask();
                typeIds[i] = encodings[i].getTypeIds();
            }
            List<Embedding> embeddings = infer(ids, masks, typeIds);
            return new EmbeddingResponse(embeddings);
        } catch (OrtException exception) {
            throw new IllegalStateException("LOCAL_EMBEDDING_INFERENCE_FAILED", exception);
        }
    }

    private List<Embedding> infer(long[][] ids, long[][] masks, long[][] typeIds) throws OrtException {
        try (OnnxTensor inputIds = OnnxTensor.createTensor(environment, ids);
             OnnxTensor attentionMask = OnnxTensor.createTensor(environment, masks);
             OnnxTensor tokenTypeIds = inputNames.contains("token_type_ids")
                     ? OnnxTensor.createTensor(environment, typeIds) : null) {
            Map<String, OnnxTensor> inputs = new HashMap<>();
            if (inputNames.contains("input_ids")) inputs.put("input_ids", inputIds);
            if (inputNames.contains("attention_mask")) inputs.put("attention_mask", attentionMask);
            if (tokenTypeIds != null) inputs.put("token_type_ids", tokenTypeIds);
            try (OrtSession.Result outputs = session.run(inputs)) {
                OnnxValue value = outputs.get("last_hidden_state").orElseThrow();
                float[][][] tokenEmbeddings = (float[][][]) value.getValue();
                List<Embedding> result = new ArrayList<>(tokenEmbeddings.length);
                for (int batch = 0; batch < tokenEmbeddings.length; batch++) {
                    if (tokenEmbeddings[batch].length == 0 || tokenEmbeddings[batch][0].length != VECTOR_DIMENSIONS) {
                        throw new IllegalStateException("LOCAL_EMBEDDING_DIMENSION_MISMATCH");
                    }
                    float[] cls = l2Normalize(tokenEmbeddings[batch][0]);
                    result.add(new Embedding(cls, batch));
                }
                return result;
            }
        }
    }

    static float[] l2Normalize(float[] source) {
        double squareSum = 0;
        for (float value : source) squareSum += (double) value * value;
        if (!Double.isFinite(squareSum) || squareSum <= 0) throw new IllegalArgumentException("Embedding 向量无效");
        double norm = Math.sqrt(squareSum);
        float[] normalized = new float[source.length];
        for (int i = 0; i < source.length; i++) normalized[i] = (float) (source[i] / norm);
        return normalized;
    }

    private String sha256(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) >= 0; ) digest.update(buffer, 0, count);
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM 不支持 SHA-256", exception);
        }
    }

    private String classify(Exception exception) {
        String message = exception.getMessage();
        if (message != null && message.matches("[A-Z0-9_]+")) return message;
        if (exception instanceof IOException) return "MODEL_LOAD_FAILED";
        if (exception instanceof OrtException) return "ONNX_RUNTIME_UNAVAILABLE";
        return "MODEL_INITIALIZATION_FAILED";
    }

    @Override
    public void close() {
        if (!closeRequested.compareAndSet(false, true)) return;
        try {
            if (tokenizer != null) tokenizer.close();
        } finally {
            if (session != null) {
                try { session.close(); }
                catch (OrtException exception) { log.warn("关闭本地 ONNX 会话时遇到问题", exception); }
            }
        }
    }
}
