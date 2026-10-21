package cn.daxpay.open.paylens.analysis;

import java.util.List;

public interface KnowledgeRetriever {
    List<KnowledgeChunk> retrieve(DiagnosticInput input, int limit);
    String version();
    default String mode() { return "KEYWORD"; }
    default List<KnowledgeChunk> retrieve(DiagnosticInput input, int limit, String mode) {
        if (!"KEYWORD".equals(mode)) throw new IllegalStateException("RETRIEVAL_MODE_UNAVAILABLE");
        return retrieve(input, limit);
    }
    default KnowledgeRetrieval retrieveDetailed(DiagnosticInput input, int limit, String mode) {
        List<KnowledgeChunk> chunks = retrieve(input, limit, mode);
        List<KnowledgeRetrieval.Hit> hits = new java.util.ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            hits.add(new KnowledgeRetrieval.Hit(chunks.get(i), i + 1, null, null, 1.0 / (61 + i)));
        }
        return new KnowledgeRetrieval(mode, version(), hits);
    }
}
