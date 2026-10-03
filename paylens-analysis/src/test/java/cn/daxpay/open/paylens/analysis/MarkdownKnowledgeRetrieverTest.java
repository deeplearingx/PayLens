package cn.daxpay.open.paylens.analysis;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownKnowledgeRetrieverTest {
    private final MarkdownKnowledgeRetriever retriever = new MarkdownKnowledgeRetriever();

    @Test
    void retrievesLateSuccessGuidanceByChineseKeywordAndStatuses() {
        DiagnosticInput input = new DiagnosticInput("T-1", "close", "success", "late_success",
                List.of(evidence("E-1", "关单后迟到的回调显示支付成功")));

        List<KnowledgeChunk> result = retriever.retrieve(input, 3);

        assertTrue(result.stream().anyMatch(chunk -> chunk.id().equals("late-success-after-close")));
    }

    @Test
    void retrievesLateSuccessGuidanceFromDaxPayClosePaidEvidenceSnapshot() {
        DiagnosticInput input = new DiagnosticInput("T-1", "close", "success", "close",
                List.of(evidence("E-1", "已建立异常处理记录；异常类型=close_paid；发现时本地状态=close；通道状态=success"),
                        evidence("E-2", "模拟终态关单后到达的通道成功回调；模拟通道状态=SUCCESS")));

        List<KnowledgeChunk> result = retriever.retrieve(input, 3);

        assertTrue(result.stream().anyMatch(chunk -> chunk.id().equals("late-success-after-close")));
    }

    @Test
    void incidentRetrievalAllowsKnowledgeAcrossAggregatedPaymentStatuses() {
        DiagnosticInput input = new DiagnosticInput("INCIDENT", "MULTIPLE", "MULTIPLE", "INCIDENT",
                List.of(evidence("E-1", "关单后收到成功回调，平台终态为 close_paid，通道明确成功")));

        List<KnowledgeChunk> result = retriever.retrieve(input, 3);

        assertTrue(result.stream().anyMatch(chunk -> chunk.id().equals("late-success-after-close")));
    }

    @Test
    void doesNotReturnProcessingOnlyGuidanceForTerminalStatusMismatch() {
        DiagnosticInput input = new DiagnosticInput("T-2", "close", "success", "late_success",
                List.of(evidence("E-2", "订单关单后收到成功通知")));

        List<KnowledgeChunk> result = retriever.retrieve(input, 10);

        assertTrue(result.stream().allMatch(chunk -> chunk.statuses().isEmpty() || chunk.statuses().contains("ANY") ||
                chunk.statuses().contains("close") || chunk.statuses().contains("success")));
        assertTrue(retriever.retrieve(input, 3).size() <= 3);
    }

    @Test
    void returnsEmptyWhenNoKeywordMatchesAndLimitIsPositive() {
        DiagnosticInput input = new DiagnosticInput("T-3", "processing", "UNKNOWN", "unmapped_issue",
                List.of(evidence("E-3", "无可匹配的特殊情况标记")));

        assertTrue(retriever.retrieve(input, 3).isEmpty());
        assertTrue(retriever.retrieve(input, 0).isEmpty());
    }

    private DiagnosticEvidence evidence(String id, String summary) {
        return new DiagnosticEvidence(id, "SIMULATED_EVENT", OffsetDateTime.parse("2026-09-24T10:00:00Z"), summary);
    }
}
