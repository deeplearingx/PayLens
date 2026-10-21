package cn.daxpay.open.paylens.analysis;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IncidentAnalysisContractTest {
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final IncidentAnalysisJsonValidator validator = new IncidentAnalysisJsonValidator(mapper);
    private final MockDiagnosticModel mockModel = new MockDiagnosticModel(mapper);

    @Test
    void mockModelOutputPassesTheSameValidatorUsedForCloudResponses() throws Exception {
        IncidentAnalysisInput input = new IncidentAnalysisInput(Map.of("observationCount", 2), List.of(
                evidence("E-callback", "CALLBACK"), evidence("E-query", "QUERY")), 0);
        String prompt = mapper.writeValueAsString(Map.of("incident", Map.of("statistics", input.statistics(),
                "omittedEvidenceCount", 0), "evidence", input.evidence(), "knowledgeIds", List.of("K-1"),
                "knowledge", List.of(Map.of("id", "K-1", "title", "查单超时", "content", "核对通道最终状态"))));

        IncidentAnalysisResult result = validator.validate(mockModel.analyze(prompt), input, Set.of("K-1"));

        assertEquals("NOT_USED", result.knowledgeUseStatus());
        assertFalse(result.facts().isEmpty());
        assertEquals(List.of("E-callback"), result.facts().getFirst().evidenceIds());
        assertEquals("paylens-mock-v1", mockModel.modelId());
    }

    @Test
    void mockModelWithNoEvidenceProducesNoInventedFactsOrCauses() throws Exception {
        IncidentAnalysisInput input = new IncidentAnalysisInput(Map.of("observationCount", 0), List.of(), 0);
        String prompt = mapper.writeValueAsString(Map.of("incident", Map.of("statistics", input.statistics(),
                "omittedEvidenceCount", 0), "evidence", List.of(), "knowledgeIds", List.of(), "knowledge", List.of()));

        IncidentAnalysisResult result = validator.validate(mockModel.analyze(prompt), input, Set.of());

        assertEquals(List.of(), result.facts());
        assertEquals(List.of(), result.differences());
        assertEquals(List.of(), result.possibleExplanations());
        assertEquals("NO_MATCH", result.knowledgeUseStatus());
        assertFalse(result.missingInformation().isEmpty());
    }

    @Test
    void validatorRejectsEvidenceIdsOutsideTheImmutableInput() {
        IncidentAnalysisInput input = new IncidentAnalysisInput(Map.of(), List.of(evidence("E-real", "CALLBACK")), 0);
        String forged = """
                {"commonPhenomenon":"现象","facts":[{"statement":"事实","evidenceIds":["E-forged"]}],
                 "differences":[],"possibleExplanations":[],"checkSteps":[],"missingInformation":[],
                 "knowledgeCitations":[],"knowledgeOmissionReason":null}
                """;

        DiagnosticValidationException error = assertThrows(DiagnosticValidationException.class,
                () -> validator.validate(forged, input, Set.of()));
        assertEquals("INVALID_EVIDENCE_REFERENCE", error.failureCode());
    }

    @Test
    void validJsonWithWrongFieldTypeIsReportedAsStructureFailure() {
        IncidentAnalysisInput input = new IncidentAnalysisInput(Map.of(), List.of(evidence("E-real", "CALLBACK")), 0);
        String wrongType = """
                {"commonPhenomenon":"现象","facts":[],"differences":[],
                 "possibleExplanations":[{"hypothesis":"假设","evidenceIds":["E-real"],"verification":["步骤一","步骤二"]}],
                 "checkSteps":[],"missingInformation":[],"knowledgeCitations":[],"knowledgeOmissionReason":null}
                """;

        DiagnosticValidationException error = assertThrows(DiagnosticValidationException.class,
                () -> validator.validate(wrongType, input, Set.of()));
        assertEquals("INVALID_STRUCTURE", error.failureCode());
    }

    @Test
    void missingArraysAndNullMembersCannotDisappearDuringRecordConstruction() {
        IncidentAnalysisInput input = new IncidentAnalysisInput(Map.of(), List.of(evidence("E-real", "QUERY")), 0);
        String missing = """
                {"commonPhenomenon":"现象","facts":[],"differences":[],"possibleExplanations":[],
                 "checkSteps":[],"knowledgeCitations":[],"knowledgeOmissionReason":null}
                """;
        String nullMember = """
                {"commonPhenomenon":"现象","facts":[null],"differences":[],"possibleExplanations":[],
                 "checkSteps":[],"missingInformation":[],"knowledgeCitations":[],"knowledgeOmissionReason":null}
                """;
        assertEquals("INVALID_STRUCTURE", assertThrows(DiagnosticValidationException.class,
                () -> validator.validate(missing, input, Set.of())).failureCode());
        assertEquals("INVALID_STRUCTURE", assertThrows(DiagnosticValidationException.class,
                () -> validator.validate(nullMember, input, Set.of())).failureCode());
    }

    @Test
    void rejectsOutOfScopeKnowledgeAndKeepsRepairContextBounded() throws Exception {
        IncidentAnalysisInput input = new IncidentAnalysisInput(Map.of(), List.of(evidence("E-real", "QUERY")), 0);
        String outOfScope = """
                {"commonPhenomenon":"现象","facts":[],"differences":[],"possibleExplanations":[],
                 "checkSteps":[],"missingInformation":[],"knowledgeCitations":["K-forged"],
                 "knowledgeOmissionReason":null}
                """;
        assertEquals("INVALID_KNOWLEDGE_CITATION", assertThrows(DiagnosticValidationException.class,
                () -> validator.validate(outOfScope, input, Set.of("K-real"))).failureCode());
        String repair = IncidentFormatRepair.prompt("原始证据 E-real", "{invalid", "INVALID_JSON", mapper);
        assertEquals("INVALID_JSON", mapper.readTree(repair).get("validationError").asText());
        assertEquals("REPAIR_CONTEXT_TOO_LARGE", assertThrows(DiagnosticValidationException.class,
                () -> IncidentFormatRepair.prompt("x".repeat(16 * 1024), "{}", "INVALID_JSON", mapper)).failureCode());
    }

    @Test
    void overlongContentIsNotClassifiedAsRepairableStructureError() {
        IncidentAnalysisInput input = new IncidentAnalysisInput(Map.of(), List.of(), 0);
        String overlong = "{\"commonPhenomenon\":\"" + "x".repeat(1201) + "\",\"facts\":[],\"differences\":[],"
                + "\"possibleExplanations\":[],\"checkSteps\":[],\"missingInformation\":[],"
                + "\"knowledgeCitations\":[],\"knowledgeOmissionReason\":null}";
        assertEquals("INVALID_LIMIT", assertThrows(DiagnosticValidationException.class,
                () -> validator.validate(overlong, input, Set.of())).failureCode());
    }

    private DiagnosticEvidence evidence(String id, String type) {
        return new DiagnosticEvidence(id, type, OffsetDateTime.of(2026, 9, 26, 10, 0, 0, 0, ZoneOffset.UTC),
                "脱敏后的支付观察记录");
    }
}
