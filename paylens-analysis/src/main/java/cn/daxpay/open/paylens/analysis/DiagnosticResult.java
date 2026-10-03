package cn.daxpay.open.paylens.analysis;

import java.util.List;

public record DiagnosticResult(String summary, List<Fact> facts, List<Cause> possibleCauses,
                              List<String> checkSteps, List<String> missingInformation,
                              List<String> knowledgeCitations, String knowledgeUseStatus,
                              String knowledgeOmissionReason) {
    public DiagnosticResult {
        facts = List.copyOf(facts);
        possibleCauses = List.copyOf(possibleCauses);
        checkSteps = List.copyOf(checkSteps);
        missingInformation = List.copyOf(missingInformation);
        knowledgeCitations = List.copyOf(knowledgeCitations);
    }

    public record Fact(String statement, List<String> evidenceIds) {
        public Fact { evidenceIds = List.copyOf(evidenceIds); }
    }

    public record Cause(String hypothesis, List<String> evidenceIds, String verification) {
        public Cause { evidenceIds = List.copyOf(evidenceIds); }
    }

    public DiagnosticResult withKnowledgeUse(String status, String omissionReason) {
        return new DiagnosticResult(summary, facts, possibleCauses, checkSteps, missingInformation,
                knowledgeCitations, status, omissionReason);
    }
}
