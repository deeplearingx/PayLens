package cn.daxpay.open.paylens.analysis;

import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** In-memory hybrid ranking; the embedding index is built away from request threads. */
@Component
@Primary
@ConditionalOnProperty(prefix = "paylens.analysis", name = "enabled", havingValue = "true")
public class HybridKnowledgeRetriever implements KnowledgeRetriever {
    private static final String QUERY_PREFIX = "为这个句子生成表示以用于检索相关文章：";
    private static final String PREPROCESS_VERSION = "bge-cls-l2-query-prefix-v1";
    private final MarkdownKnowledgeRetriever keyword;
    private final ObjectProvider<BgeSmallZhOnnxEmbeddingModel> modelProvider;
    private final ObjectProvider<ThreadPoolTaskExecutor> executorProvider;
    private final boolean enabled;
    private final double threshold;
    private volatile Index index;
    private volatile boolean building;

    public HybridKnowledgeRetriever(MarkdownKnowledgeRetriever keyword,
                                    ObjectProvider<BgeSmallZhOnnxEmbeddingModel> modelProvider,
                                    @Qualifier("payLensEmbeddingExecutor") ObjectProvider<ThreadPoolTaskExecutor> executorProvider,
                                    @Value("${paylens.analysis.hybrid-enabled:false}") boolean enabled,
                                    @Value("${paylens.analysis.hybrid-threshold:0.54}") double threshold) {
        if (threshold < 0.5 || threshold > 0.98) throw new IllegalArgumentException("混合检索阈值超出范围");
        this.keyword = keyword;
        this.modelProvider = modelProvider;
        this.executorProvider = executorProvider;
        this.enabled = enabled;
        this.threshold = threshold;
    }

    @Scheduled(fixedDelay = 5000)
    public void initializeIfReady() {
        if (!enabled || building) return;
        BgeSmallZhOnnxEmbeddingModel model = modelProvider.getIfAvailable();
        if (model == null || !model.isReady()) return;
        if (index != null && model.modelSha256().equals(index.modelHash())) return;
        ThreadPoolTaskExecutor executor = executorProvider.getIfAvailable();
        if (executor == null) return;
        building = true;
        try {
            executor.execute(() -> {
                try { index = buildIndex(model); }
                catch (RuntimeException ignored) { index = null; }
                finally { building = false; }
            });
        } catch (RuntimeException exception) { building = false; }
    }

    private Index buildIndex(BgeSmallZhOnnxEmbeddingModel model) {
        List<KnowledgeChunk> chunks = keyword.chunks();
        List<String> texts = chunks.stream().map(c -> c.title() + "\n" + c.content()).toList();
        var embeddings = model.call(new EmbeddingRequest(texts, null)).getResults();
        if (embeddings.size() != chunks.size()) throw new IllegalStateException("KNOWLEDGE_EMBEDDING_MISMATCH");
        Map<String, float[]> vectors = new HashMap<>();
        for (int i = 0; i < chunks.size(); i++) vectors.put(chunks.get(i).id(), embeddings.get(i).getOutput());
        return new Index(model.modelSha256(), keyword.version() + ":" + PREPROCESS_VERSION, Map.copyOf(vectors));
    }

    @Override public String version() { return keyword.version() + "|hybrid-v1|" + PREPROCESS_VERSION + "|" + enabled + "|" + threshold; }

    @Override public String mode() {
        Index current = index;
        BgeSmallZhOnnxEmbeddingModel model = modelProvider.getIfAvailable();
        return enabled && current != null && model != null && model.isReady()
                && current.modelHash().equals(model.modelSha256()) && current.knowledgeVersion().equals(keyword.version() + ":" + PREPROCESS_VERSION)
                ? "HYBRID:" + current.modelHash() : "KEYWORD";
    }

    @Override public List<KnowledgeChunk> retrieve(DiagnosticInput input, int limit) {
        return retrieve(input, limit, mode());
    }

    @Override public List<KnowledgeChunk> retrieve(DiagnosticInput input, int limit, String frozenMode) {
        return retrieveDetailed(input, limit, frozenMode).chunks();
    }

    @Override public KnowledgeRetrieval retrieveDetailed(DiagnosticInput input, int limit, String frozenMode) {
        if (limit < 1) return new KnowledgeRetrieval(frozenMode, version(), List.of());
        if ("KEYWORD".equals(frozenMode)) {
            return new KnowledgeRetrieval(frozenMode, version(), keyword.retrieveDetailed(input, limit, frozenMode).hits());
        }
        if (!frozenMode.equals(mode())) throw new IllegalStateException("RETRIEVAL_MODE_UNAVAILABLE");
        Index current = index;
        BgeSmallZhOnnxEmbeddingModel model = modelProvider.getIfAvailable();
        if (current == null || model == null) throw new IllegalStateException("RETRIEVAL_MODE_UNAVAILABLE");

        String query = input.abnormalType() + " " + input.localStatus() + " " + input.channelStatus() + " " +
                input.evidence().stream().map(DiagnosticEvidence::summary).reduce("", (a, b) -> a + " " + b);
        float[] queryVector = model.call(new EmbeddingRequest(List.of(QUERY_PREFIX + query), null))
                .getResults().getFirst().getOutput();
        List<Scored> semantic = keyword.chunks().stream()
                .map(chunk -> new Scored(chunk, cosine(queryVector, current.vectors().get(chunk.id()))))
                .filter(item -> item.score() >= threshold)
                .sorted(Comparator.comparingDouble(Scored::score).reversed().thenComparing(item -> item.chunk().id()))
                .limit(5).toList();
        List<KnowledgeChunk> lexical = keyword.retrieve(input, 5);
        Map<String, Double> rankScore = new HashMap<>();
        Map<String, Double> semanticScore = new HashMap<>();
        semantic.forEach(item -> semanticScore.put(item.chunk().id(), item.score()));
        for (int i = 0; i < lexical.size(); i++) rankScore.merge(lexical.get(i).id(), 1.0 / (60 + i + 1), Double::sum);
        for (int i = 0; i < semantic.size(); i++) rankScore.merge(semantic.get(i).chunk().id(), 1.0 / (60 + i + 1), Double::sum);
        List<KnowledgeChunk> ranked = keyword.chunks().stream().filter(chunk -> rankScore.containsKey(chunk.id()))
                .sorted(Comparator.<KnowledgeChunk>comparingDouble(chunk -> rankScore.get(chunk.id())).reversed()
                        .thenComparing(KnowledgeChunk::id)).limit(limit).toList();
        List<KnowledgeRetrieval.Hit> hits = new ArrayList<>();
        for (int i = 0; i < ranked.size(); i++) {
            KnowledgeChunk chunk = ranked.get(i);
            hits.add(new KnowledgeRetrieval.Hit(chunk, i + 1,
                    lexical.contains(chunk) ? (double) keyword.keywordScore(input, chunk) : null,
                    semanticScore.get(chunk.id()), rankScore.get(chunk.id())));
        }
        return new KnowledgeRetrieval(frozenMode, version(), hits);
    }

    private static double cosine(float[] first, float[] second) {
        if (second == null || first.length != second.length) throw new IllegalStateException("KNOWLEDGE_EMBEDDING_DIMENSION_MISMATCH");
        double score = 0;
        for (int i = 0; i < first.length; i++) score += (double) first[i] * second[i];
        return Math.max(-1, Math.min(1, score));
    }

    private record Index(String modelHash, String knowledgeVersion, Map<String, float[]> vectors) {}
    private record Scored(KnowledgeChunk chunk, double score) {}
}
