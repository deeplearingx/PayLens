package cn.daxpay.open.paylens.analysis;

import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Assumptions;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BgeSmallZhOnnxEmbeddingModelTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void clsVectorsAreL2NormalizedWithoutMutatingTheOnnxOutput() {
        float[] source = {3.0f, 4.0f};

        float[] normalized = BgeSmallZhOnnxEmbeddingModel.l2Normalize(source);

        assertEquals(3.0f, source[0]);
        assertEquals(4.0f, source[1]);
        assertEquals(0.6f, normalized[0], 0.00001f);
        assertEquals(0.8f, normalized[1], 0.00001f);
        assertEquals(1.0, Math.sqrt(normalized[0] * normalized[0] + normalized[1] * normalized[1]), 0.00001);
    }

    @Test
    void rejectsZeroOrInvalidVectorsInsteadOfReturningAnInvalidSimilarityInput() {
        assertThrows(IllegalArgumentException.class, () -> BgeSmallZhOnnxEmbeddingModel.l2Normalize(new float[]{0, 0}));
        assertThrows(IllegalArgumentException.class, () -> BgeSmallZhOnnxEmbeddingModel.l2Normalize(new float[]{Float.NaN}));
    }

    @Test
    void missingModelFilesAreReportedWithoutAttemptingToDownloadThem() {
        PayLensEmbeddingProperties properties = new PayLensEmbeddingProperties();
        properties.setModelDirectory(temporaryDirectory.resolve("missing-model").toString());
        BgeSmallZhOnnxEmbeddingModel model = new BgeSmallZhOnnxEmbeddingModel(properties, new ThreadPoolTaskExecutor());

        IOException error = assertThrows(IOException.class, model::initializeLocalFiles);

        assertEquals("MODEL_FILES_MISSING", error.getMessage());
        assertTrue(model.failureCode() == null); // Only the async bootstrap records its stable fallback state.
        assertTrue(!model.isReady());
        model.close();
    }

    @Test
    void modelIdentityAndHashesAreCheckedBeforeOnnxRuntimeLoadsTheGraph() throws Exception {
        Path directory = temporaryDirectory.resolve("model");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("model.onnx"), "not-an-onnx-model");
        Files.writeString(directory.resolve("tokenizer.json"), "{}");
        Files.writeString(directory.resolve("model-manifest.properties"), """
                model=wrong/model
                revision=0123456789abcdef
                model.onnx.sha256=wrong
                tokenizer.json.sha256=wrong
                """);
        PayLensEmbeddingProperties properties = new PayLensEmbeddingProperties();
        properties.setModelDirectory(directory.toString());
        BgeSmallZhOnnxEmbeddingModel model = new BgeSmallZhOnnxEmbeddingModel(properties, new ThreadPoolTaskExecutor());

        IOException error = assertThrows(IOException.class, model::initializeLocalFiles);

        assertEquals("MODEL_ID_MISMATCH", error.getMessage());
        model.close();
    }

    @Test
    void javaOnnxVectorsMatchThePinnedTransformersReferenceAndPreserveSimilarityRanking() throws Exception {
        String configuredPath = System.getenv().getOrDefault("PAYLENS_EMBEDDING_MODEL_DIR", "models/bge-small-zh-v1.5");
        Path modelDirectory = Path.of(configuredPath).toAbsolutePath().normalize();
        if (!Files.isRegularFile(modelDirectory.resolve("model.onnx"))) {
            Path repositoryRelative = Path.of("..", configuredPath).toAbsolutePath().normalize();
            if (Files.isRegularFile(repositoryRelative.resolve("model.onnx"))) modelDirectory = repositoryRelative;
        }
        Assumptions.assumeTrue(Files.isRegularFile(modelDirectory.resolve("model.onnx"))
                        && Files.isRegularFile(modelDirectory.resolve("reference-validation.json")),
                "Run scripts/prepare-bge-model.ps1 before local ONNX parity verification.");

        PayLensEmbeddingProperties properties = new PayLensEmbeddingProperties();
        properties.setModelDirectory(modelDirectory.toString());
        BgeSmallZhOnnxEmbeddingModel model = new BgeSmallZhOnnxEmbeddingModel(properties, new ThreadPoolTaskExecutor());
        try {
            model.initializeLocalFiles();
            List<String> texts = List.of(
                    "支付查单时通道长时间没有响应，超过了读取等待时限。",
                    "发起支付查询后一直等不到通道答复，最终触发读取超时。",
                    "支付回调的签名校验未通过，证书或密钥与通道配置不一致。",
                    "退款查询期间通道没有及时返回，客户端等待超时。");
            List<Embedding> embeddings = model.call(new EmbeddingRequest(texts, null)).getResults();
            assertEquals(4, embeddings.size());
            for (Embedding embedding : embeddings) {
                float[] vector = embedding.getOutput();
                assertEquals(512, vector.length);
                double norm = Math.sqrt(java.util.Arrays.stream(toDoubleArray(vector)).map(value -> value * value).sum());
                assertEquals(1.0, norm, 0.00001);
            }
            double samePhenomenon = cosine(embeddings.get(0).getOutput(), embeddings.get(1).getOutput());
            double differentPhenomenon = cosine(embeddings.get(0).getOutput(), embeddings.get(2).getOutput());
            var reference = JsonMapper.builder().build().readTree(
                    Files.readString(modelDirectory.resolve("reference-validation.json")));
            assertEquals(reference.path("samePhenomenonSimilarity").doubleValue(), samePhenomenon, 0.002);
            assertEquals(reference.path("differentPhenomenonSimilarity").doubleValue(), differentPhenomenon, 0.002);
            assertTrue(samePhenomenon > differentPhenomenon);
            assertTrue(model.isReady());
            assertEquals("7999e1d3359715c523056ef9478215996d62a620", model.modelRevision());
        } finally {
            model.close();
        }
    }

    private double[] toDoubleArray(float[] source) {
        double[] result = new double[source.length];
        for (int i = 0; i < source.length; i++) result[i] = source[i];
        return result;
    }

    private double cosine(float[] left, float[] right) {
        double score = 0;
        for (int i = 0; i < left.length; i++) score += (double) left[i] * right[i];
        return score;
    }
}
