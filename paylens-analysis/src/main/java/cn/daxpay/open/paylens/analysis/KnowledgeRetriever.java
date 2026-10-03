package cn.daxpay.open.paylens.analysis;

import java.util.List;

public interface KnowledgeRetriever {
    List<KnowledgeChunk> retrieve(DiagnosticInput input, int limit);
    String version();
}
