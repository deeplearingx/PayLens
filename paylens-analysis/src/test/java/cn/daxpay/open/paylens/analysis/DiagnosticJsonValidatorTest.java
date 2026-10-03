package cn.daxpay.open.paylens.analysis;

import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DiagnosticJsonValidatorTest {
    private final DiagnosticJsonValidator validator = new DiagnosticJsonValidator(JsonMapper.builder().build());
    private final DiagnosticInput input = new DiagnosticInput("T-1", "close", "success", "late-success",
            List.of(new DiagnosticEvidence("E-1", "TRADE", OffsetDateTime.parse("2026-09-24T10:00:00Z"), "订单已关闭")));

    @Test
    void acceptsOnlyEvidenceAndKnowledgeIdsFromCurrentRequest() {
        String json = """
                {"summary":"本地状态与通道证据不一致","facts":[{"statement":"平台订单已关闭","evidenceIds":["E-1"]}],
                "possibleCauses":[{"hypothesis":"回调到达较晚","evidenceIds":["E-1"],"verification":"查询通道最终状态"}],
                "checkSteps":["核对通道订单"],"missingInformation":[],"knowledgeCitations":["K-1"]}
                """;

        DiagnosticResult result = validator.validate(json, input, Set.of("K-1"));

        assertEquals("本地状态与通道证据不一致", result.summary());
        assertEquals(List.of("K-1"), result.knowledgeCitations());
    }

    @Test
    void rejectsEvidenceIdNotPresentInSnapshot() {
        String json = """
                {"summary":"摘要","facts":[{"statement":"未经证实的事实","evidenceIds":["E-FABRICATED"]}],
                "possibleCauses":[],"checkSteps":[],"missingInformation":[],"knowledgeCitations":[]}
                """;

        assertThrows(IllegalArgumentException.class, () -> validator.validate(json, input, Set.of()));
    }

    @Test
    void rejectsKnowledgeCitationNotReturnedByRetriever() {
        String json = """
                {"summary":"摘要","facts":[],"possibleCauses":[],"checkSteps":[],
                "missingInformation":[],"knowledgeCitations":["K-FABRICATED"]}
                """;

        assertThrows(IllegalArgumentException.class, () -> validator.validate(json, input, Set.of("K-1")));
    }

    @Test
    void requiresCitationWhenKnowledgeWasRetrieved() {
        String json = """
                {"summary":"摘要","facts":[],"possibleCauses":[],"checkSteps":[],
                "missingInformation":[],"knowledgeCitations":[]}
                """;

        var error = assertThrows(DiagnosticValidationException.class,
                () -> validator.validate(json, input, Set.of("K-1")));
        assertEquals("MISSING_KNOWLEDGE_DECISION", error.failureCode());
    }

    @Test
    void acceptsExplicitKnowledgeOmissionAndLabelsServerSideStatus() {
        String json = """
                {"summary":"摘要","facts":[],"possibleCauses":[],"checkSteps":[],
                "missingInformation":[],"knowledgeCitations":[],"knowledgeOmissionReason":"内容与本案无关"}
                """;

        DiagnosticResult result = validator.validate(json, input, Set.of("K-1"));

        assertEquals("NOT_USED", result.knowledgeUseStatus());
        assertEquals("内容与本案无关", result.knowledgeOmissionReason());
    }

    @Test
    void marksNoRetrievedKnowledgeWithoutTrustingModelStatus() {
        String json = """
                {"summary":"摘要","facts":[],"possibleCauses":[],"checkSteps":[],
                "missingInformation":[],"knowledgeCitations":[],"knowledgeUseStatus":"CITED"}
                """;

        DiagnosticResult result = validator.validate(json, input, Set.of());

        assertEquals("NO_MATCH", result.knowledgeUseStatus());
    }

    @Test
    void rejectsFactWithoutEvidenceAndMalformedJson() {
        String factWithoutEvidence = """
                {"summary":"摘要","facts":[{"statement":"无依据断言","evidenceIds":[]}],
                "possibleCauses":[],"checkSteps":[],"missingInformation":[],"knowledgeCitations":[]}
                """;

        assertThrows(IllegalArgumentException.class, () -> validator.validate(factWithoutEvidence, input, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> validator.validate("not-json", input, Set.of()));
    }

    @Test
    void rejectsPossibleCauseWithoutEvidence() {
        String causeWithoutEvidence = """
                {"summary":"摘要","facts":[],
                "possibleCauses":[{"hypothesis":"可能是回调延迟","evidenceIds":[],"verification":"查询通道回调记录"}],
                "checkSteps":[],"missingInformation":[],"knowledgeCitations":[]}
                """;

        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(causeWithoutEvidence, input, Set.of()));
    }
}
