package cn.daxpay.open.paylens.analysis;

import java.util.List;

public record KnowledgeChunk(String id, String title, List<String> keywords, List<String> statuses, String content) {
    public KnowledgeChunk {
        keywords = List.copyOf(keywords);
        statuses = List.copyOf(statuses);
    }
}
