package cn.daxpay.open.paylens.analysis;

import java.time.OffsetDateTime;
import java.util.List;

public record IncidentAnalysisExecution(OffsetDateTime snapshotAt, String snapshotHash, String modelId,
                                        String modelConfigurationId, String promptVersion,
                                        String outputSchemaVersion, String knowledgeVersion, String retrievalMode,
                                        List<KnowledgeChunk> retrievedKnowledge, boolean legacy) {
    public IncidentAnalysisExecution {
        retrievedKnowledge = retrievedKnowledge == null ? List.of() : List.copyOf(retrievedKnowledge);
    }
    public IncidentAnalysisExecution(OffsetDateTime snapshotAt, String snapshotHash, String modelId,
                                     String modelConfigurationId, String promptVersion, String outputSchemaVersion,
                                     String knowledgeVersion, List<KnowledgeChunk> retrievedKnowledge, boolean legacy) {
        this(snapshotAt, snapshotHash, modelId, modelConfigurationId, promptVersion,
                outputSchemaVersion, knowledgeVersion, "KEYWORD", retrievedKnowledge, legacy);
    }
}
