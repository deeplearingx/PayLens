package cn.daxpay.open.paylens.analysis;

import java.time.OffsetDateTime;
import java.util.List;

public record AnalysisExecutionMetadata(
        OffsetDateTime snapshotAt,
        String snapshotHash,
        String modelId,
        String modelConfigurationId,
        String promptVersion,
        String outputSchemaVersion,
        String knowledgeVersion,
        List<KnowledgeChunk> retrievedKnowledge,
        boolean legacy) {
    public AnalysisExecutionMetadata {
        retrievedKnowledge = retrievedKnowledge == null ? List.of() : List.copyOf(retrievedKnowledge);
    }
}
