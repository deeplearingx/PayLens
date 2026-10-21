package cn.daxpay.open.paylens.analysis;

import java.util.List;

/** Ranking trace stays inside the application; prompts receive only the selected chunks. */
public record KnowledgeRetrieval(String mode, String version, List<Hit> hits) {
    public KnowledgeRetrieval { hits = List.copyOf(hits); }
    public List<KnowledgeChunk> chunks() { return hits.stream().map(Hit::chunk).toList(); }
    public record Hit(KnowledgeChunk chunk, int rank, Double keywordScore,
                      Double vectorScore, double fusedScore) { }
}
