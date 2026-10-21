package cn.daxpay.open.paylens.analysis;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

class IncidentAnalysisRepairTest {
    @Test
    void secondMalformedResponseFailsAfterExactlyTwoRecordedCalls() throws Exception {
        Fixture fixture = fixture();
        when(fixture.model.analyzeDetailed(any())).thenReturn(
                new DiagnosticModelResponse("{bad", 10L, 5L, "mock"),
                new DiagnosticModelResponse("{still bad", 11L, 6L, "mock"));
        when(fixture.repository.startModelCall(eq("TASK"), eq("INITIAL"), eq("mock"))).thenReturn(1L);
        when(fixture.repository.startModelCall(eq("TASK"), eq("FORMAT_REPAIR"), eq("mock"))).thenReturn(2L);

        fixture.run();

        verify(fixture.model, times(2)).analyzeDetailed(any());
        verify(fixture.repository).recordModelResponse(eq(1L), any());
        verify(fixture.repository).recordModelResponse(eq(2L), any());
        verify(fixture.repository).finishModelCall(eq(1L), eq("FAILED"), eq("INVALID_JSON"), anyLong(), any());
        verify(fixture.repository).finishModelCall(eq(2L), eq("FAILED"), eq("INVALID_JSON"), anyLong(), any());
        verify(fixture.repository).markFailed(eq("TASK"), eq("INVALID_JSON"), anyLong());
    }

    @Test
    void outOfScopeCitationFailsWithoutFormatRepair() throws Exception {
        Fixture fixture = fixture();
        String forged = """
                {"commonPhenomenon":"查单未知","facts":[{"statement":"已确认成功","evidenceIds":["E-forged"]}],
                 "differences":[],"possibleExplanations":[],"checkSteps":[],"missingInformation":[],
                 "knowledgeCitations":[],"knowledgeOmissionReason":null}
                """;
        when(fixture.model.analyzeDetailed(any())).thenReturn(new DiagnosticModelResponse(forged, 10L, 5L, "mock"));
        when(fixture.repository.startModelCall(eq("TASK"), eq("INITIAL"), eq("mock"))).thenReturn(1L);

        fixture.run();

        verify(fixture.model, times(1)).analyzeDetailed(any());
        verify(fixture.repository, never()).startModelCall(eq("TASK"), eq("FORMAT_REPAIR"), any());
        verify(fixture.repository).markFailed(eq("TASK"), eq("INVALID_EVIDENCE_REFERENCE"), anyLong());
    }

    private Fixture fixture() throws Exception {
        IncidentAnalysisRepository repository = mock(IncidentAnalysisRepository.class);
        DiagnosticModel model = mock(DiagnosticModel.class);
        KnowledgeRetriever retriever = mock(KnowledgeRetriever.class);
        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        ObjectMapper mapper = JsonMapper.builder().build();
        when(repository.claimNext()).thenReturn(Optional.of("TASK"));
        when(model.configurationId()).thenReturn("config");
        when(model.modelId()).thenReturn("mock");
        when(retriever.version()).thenReturn("knowledge-v1");
        when(retriever.retrieveDetailed(any(), eq(3), eq("KEYWORD")))
                .thenReturn(new KnowledgeRetrieval("KEYWORD", "knowledge-v1", List.of()));
        when(repository.execution("TASK")).thenReturn(Optional.of(new IncidentAnalysisExecution(
                OffsetDateTime.parse("2026-10-03T00:00:00Z"), "snapshot", "mock", sha("config"),
                sha(IncidentAnalysisPromptBuilder.versionMaterial()), IncidentAnalysisPromptBuilder.SCHEMA_VERSION,
                "knowledge-v1", "KEYWORD", List.of(), false)));
        when(repository.snapshot("TASK")).thenReturn(Optional.of(new IncidentAnalysisInput(
                Map.of("observationCount", 1), List.of(new DiagnosticEvidence("E-1", "QUERY",
                OffsetDateTime.parse("2026-10-03T00:00:00Z"), "查单超时，结果未知")), 0)));
        IncidentAnalysisService service = new IncidentAnalysisService(repository, model, retriever,
                new IncidentAnalysisJsonValidator(mapper), mapper, executor, mock(JdbcTemplate.class));
        return new Fixture(service, repository, model);
    }

    private String sha(String value) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
    private record Fixture(IncidentAnalysisService service, IncidentAnalysisRepository repository,
                           DiagnosticModel model) {
        void run() throws Exception {
            Method method = IncidentAnalysisService.class.getDeclaredMethod("processNext");
            method.setAccessible(true);
            method.invoke(service);
        }
    }
}
