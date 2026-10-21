package cn.daxpay.open.paylens.analysis;

import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class DiagnosticInputSanitizationTest {
    @Test
    void removesEmailAndPhoneBeforeTheSnapshotCanBeSentToModel() {
        DiagnosticInput input = new DiagnosticInput("T-9", "processing", "UNKNOWN", "unknown",
                List.of(new DiagnosticEvidence("E-9", "CALLBACK", OffsetDateTime.parse("2026-09-24T10:00:00Z"),
                        "contact alice@example.com or 13812345678; api_key=sk-test-abcdefghijklmnop; Authorization: Bearer abcdefghijklmnop")));
        DiagnosticAnalysisService service = new DiagnosticAnalysisService(
                mock(AnalysisTaskRepository.class), mock(KnowledgeRetriever.class), mock(DiagnosticModel.class),
                mock(DiagnosticJsonValidator.class), JsonMapper.builder().build(), mock(ThreadPoolTaskExecutor.class), null);

        DiagnosticInput clean = service.sanitize(input);

        assertFalse(clean.evidence().getFirst().summary().contains("alice@example.com"));
        assertFalse(clean.evidence().getFirst().summary().contains("13812345678"));
        assertFalse(clean.evidence().getFirst().summary().contains("sk-test-abcdefghijklmnop"));
        assertFalse(clean.evidence().getFirst().summary().contains("abcdefghijklmnop"));
        assertTrue(clean.evidence().getFirst().summary().contains("[邮箱已脱敏]"));
        assertTrue(clean.evidence().getFirst().summary().contains("[手机号已脱敏]"));
        assertTrue(clean.evidence().getFirst().summary().contains("[密钥已脱敏]"));
    }

    @Test
    void masksSecretsInJsonLikeEvidence() {
        String clean = SensitiveTextSanitizer.sanitize("{\"password\":\"not-for-model\",\"access_token\":\"token-value\"}");
        assertFalse(clean.contains("not-for-model"));
        assertFalse(clean.contains("token-value"));
    }

    @Test
    void masksPaymentAndRequestReferencesWithoutRemovingDiagnosticStatus() {
        String clean = SensitiveTextSanitizer.sanitize(
                "订单号:ORD20261003001 request_id=req-998 trace_id=trace-42 HTTP 504 SocketTimeoutException payment=unknown");
        assertFalse(clean.contains("ORD20261003001"));
        assertFalse(clean.contains("req-998"));
        assertFalse(clean.contains("trace-42"));
        assertTrue(clean.contains("HTTP 504"));
        assertTrue(clean.contains("SocketTimeoutException"));
        assertTrue(clean.contains("payment=unknown"));
    }

    @Test
    void capsEvidenceCountAndIndividualSummaryLength() {
        List<DiagnosticEvidence> evidence = new ArrayList<>();
        for (int index = 0; index < 31; index++) {
            evidence.add(new DiagnosticEvidence("E-" + index, "CALLBACK",
                    OffsetDateTime.parse("2026-09-24T10:00:00Z"), "short"));
        }
        DiagnosticInput input = new DiagnosticInput("T-10", "processing", "unknown", "unknown", evidence);
        DiagnosticAnalysisService service = new DiagnosticAnalysisService(
                mock(AnalysisTaskRepository.class), mock(KnowledgeRetriever.class), mock(DiagnosticModel.class),
                mock(DiagnosticJsonValidator.class), JsonMapper.builder().build(), mock(ThreadPoolTaskExecutor.class), null);

        DiagnosticInput clean = service.sanitize(input);

        assertEquals(30, clean.evidence().size());

        DiagnosticInput longSummary = new DiagnosticInput("T-10", "processing", "unknown", "unknown",
                List.of(new DiagnosticEvidence("E-LONG", "CALLBACK",
                        OffsetDateTime.parse("2026-09-24T10:00:00Z"), "x".repeat(3000))));
        assertEquals(2000, service.sanitize(longSummary).evidence().getFirst().summary().length());
    }

    @Test
    void keepsLatestEvidenceAndRequiredTradeAnchorsWhenCapping() {
        OffsetDateTime start = OffsetDateTime.parse("2026-09-24T10:00:00Z");
        List<DiagnosticEvidence> evidence = new ArrayList<>();
        evidence.add(new DiagnosticEvidence("E-TRADE", "TRADE", start, "transaction anchor"));
        evidence.add(new DiagnosticEvidence("E-ABNORMAL", "ABNORMAL_ORDER", start.plusSeconds(1), "abnormal anchor"));
        for (int index = 0; index < 40; index++) {
            evidence.add(new DiagnosticEvidence("E-" + index, "CALLBACK", start.plusSeconds(index + 2), "callback"));
        }
        DiagnosticAnalysisService service = new DiagnosticAnalysisService(
                mock(AnalysisTaskRepository.class), mock(KnowledgeRetriever.class), mock(DiagnosticModel.class),
                mock(DiagnosticJsonValidator.class), JsonMapper.builder().build(), mock(ThreadPoolTaskExecutor.class), null);

        List<String> ids = service.sanitize(new DiagnosticInput("T-11", "processing", "unknown", "unknown", evidence))
                .evidence().stream().map(DiagnosticEvidence::id).toList();

        assertEquals(30, ids.size());
        assertTrue(ids.contains("E-TRADE"));
        assertTrue(ids.contains("E-ABNORMAL"));
        assertTrue(ids.contains("E-39"));
        assertTrue(ids.contains("E-12"));
        assertFalse(ids.contains("E-0"));
    }
}
