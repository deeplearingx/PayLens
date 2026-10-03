package cn.daxpay.open.paylens.analysis;

import java.util.List;
import java.util.Map;

/** Sanitized evidence-only context for one immutable incident revision. */
public record IncidentAnalysisInput(Map<String, Object> statistics,
                                    List<DiagnosticEvidence> evidence,
                                    int omittedEvidenceCount) {
    public IncidentAnalysisInput {
        statistics = statistics == null ? Map.of() : Map.copyOf(statistics);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        if (omittedEvidenceCount < 0) throw new IllegalArgumentException("omittedEvidenceCount must be non-negative");
    }
}
