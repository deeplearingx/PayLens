package cn.daxpay.open.paylens.analysis;

import java.util.List;

public record IncidentAnalysisResult(String commonPhenomenon,
                                     List<Fact> facts,
                                     List<Difference> differences,
                                     List<Explanation> possibleExplanations,
                                     List<String> checkSteps,
                                     List<String> missingInformation,
                                     List<String> knowledgeCitations,
                                     String knowledgeOmissionReason,
                                     String knowledgeUseStatus) {
    public IncidentAnalysisResult {
        facts = copy(facts);
        differences = copy(differences);
        possibleExplanations = copy(possibleExplanations);
        checkSteps = copy(checkSteps);
        missingInformation = copy(missingInformation);
        knowledgeCitations = copy(knowledgeCitations);
    }
    private static <T> List<T> copy(List<T> input) { return input == null ? List.of() : List.copyOf(input); }
    public record Fact(String statement, List<String> evidenceIds) {
        public Fact { evidenceIds = copy(evidenceIds); }
    }
    public record Difference(String description, List<String> evidenceIds) {
        public Difference { evidenceIds = copy(evidenceIds); }
    }
    public record Explanation(String hypothesis, List<String> evidenceIds, String verification) {
        public Explanation { evidenceIds = copy(evidenceIds); }
    }
}
