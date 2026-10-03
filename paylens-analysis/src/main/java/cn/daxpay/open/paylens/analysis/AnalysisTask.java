package cn.daxpay.open.paylens.analysis;

import java.util.List;

public record AnalysisTask(String id, String tradeNo, String status, DiagnosticResult result,
                           String failureReason, Long durationMs, boolean reused,
                           AnalysisExecutionMetadata execution, List<AnalysisAttempt> attempts) {
    public AnalysisTask {
        attempts = attempts == null ? List.of() : List.copyOf(attempts);
    }

    public AnalysisTask(String id, String tradeNo, String status, DiagnosticResult result,
                        String failureReason, Long durationMs, boolean reused) {
        this(id, tradeNo, status, result, failureReason, durationMs, reused, null, List.of());
    }

    public AnalysisTask withReused(boolean reused) {
        return new AnalysisTask(id, tradeNo, status, result, failureReason, durationMs, reused, execution, attempts);
    }
}
