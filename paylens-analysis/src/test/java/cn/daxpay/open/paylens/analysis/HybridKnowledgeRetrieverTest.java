package cn.daxpay.open.paylens.analysis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.OffsetDateTime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HybridKnowledgeRetrieverTest {
    @Test
    void noMatchStaysEmptyAndFrozenHybridCannotSilentlyFallBack() {
        @SuppressWarnings("unchecked") ObjectProvider<BgeSmallZhOnnxEmbeddingModel> model = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked") ObjectProvider<ThreadPoolTaskExecutor> executor = mock(ObjectProvider.class);
        HybridKnowledgeRetriever retriever = new HybridKnowledgeRetriever(
                new MarkdownKnowledgeRetriever(), model, executor, true, .54);
        DiagnosticInput input = new DiagnosticInput("INCIDENT", "MULTIPLE", "MULTIPLE", "INCIDENT",
                List.of(new DiagnosticEvidence("E-1", "QUERY", OffsetDateTime.parse("2026-10-03T00:00:00Z"),
                        "离线文件压缩包损坏且缺少字体")));
        assertEquals("KEYWORD", retriever.mode());
        assertEquals(List.of(), retriever.retrieve(input, 3, "KEYWORD"));
        assertThrows(IllegalStateException.class, () -> retriever.retrieve(input, 3, "HYBRID:old-model"));
    }

    @Test
    void javaHybridRankingMatchesFrozenOfflineCalibration() throws Exception {
        Path directory = Path.of("models/bge-small-zh-v1.5").toAbsolutePath();
        if (!Files.isRegularFile(directory.resolve("model.onnx")))
            directory = Path.of("../models/bge-small-zh-v1.5").toAbsolutePath();
        Assumptions.assumeTrue(Files.isRegularFile(directory.resolve("model.onnx")));
        PayLensEmbeddingProperties properties = new PayLensEmbeddingProperties();
        properties.setModelDirectory(directory.toString());
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.initialize();
        BgeSmallZhOnnxEmbeddingModel bge = new BgeSmallZhOnnxEmbeddingModel(properties, executor);
        try {
            bge.initializeLocalFiles();
            @SuppressWarnings("unchecked") ObjectProvider<BgeSmallZhOnnxEmbeddingModel> modelProvider = mock(ObjectProvider.class);
            @SuppressWarnings("unchecked") ObjectProvider<ThreadPoolTaskExecutor> executorProvider = mock(ObjectProvider.class);
            when(modelProvider.getIfAvailable()).thenReturn(bge);
            when(executorProvider.getIfAvailable()).thenReturn(executor);
            HybridKnowledgeRetriever retriever = new HybridKnowledgeRetriever(
                    new MarkdownKnowledgeRetriever(), modelProvider, executorProvider, true, .54);
            retriever.initializeIfReady();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while ("KEYWORD".equals(retriever.mode()) && System.nanoTime() < deadline) Thread.sleep(20);
            Assumptions.assumeTrue(retriever.mode().startsWith("HYBRID:"), "BGE index not ready in test window");
            DiagnosticInput input = new DiagnosticInput("INCIDENT", "MULTIPLE", "MULTIPLE", "INCIDENT",
                    List.of(new DiagnosticEvidence("E-1", "QUERY", OffsetDateTime.parse("2026-10-03T00:00:00Z"),
                            "通道流水显示已付款，平台等待回调后仍停留在处理中。")));
            assertEquals(List.of("lost-callback", "terminal-conflict", "late-success-after-close"),
                    retriever.retrieve(input, 3, retriever.mode()).stream().map(KnowledgeChunk::id).toList());
            DiagnosticInput noMatch = new DiagnosticInput("INCIDENT", "MULTIPLE", "MULTIPLE", "INCIDENT",
                    List.of(new DiagnosticEvidence("E-2", "QUERY", OffsetDateTime.parse("2026-10-03T00:00:00Z"),
                            "日志归档校验发现压缩包头部格式与预期不一致。")));
            assertEquals(List.of(), retriever.retrieve(noMatch, 3, retriever.mode()));
        } finally {
            bge.close();
            executor.shutdown();
        }
    }
}
