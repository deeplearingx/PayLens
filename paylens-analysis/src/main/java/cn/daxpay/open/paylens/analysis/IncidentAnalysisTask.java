package cn.daxpay.open.paylens.analysis;

import java.util.List;

public record IncidentAnalysisTask(String id, String incidentId, int incidentVersion, String status,
                                   IncidentAnalysisResult result, String failureCode, Long durationMs,
                                   boolean reused, IncidentAnalysisExecution execution,
                                   List<IncidentAnalysisAttempt> attempts, List<IncidentModelCall> modelCalls,
                                   Long queueDurationMs) {
    public IncidentAnalysisTask {
        attempts = attempts == null ? List.of() : List.copyOf(attempts);
        modelCalls = modelCalls == null ? List.of() : List.copyOf(modelCalls);
    }
    public IncidentAnalysisTask withReused(boolean reused) {
        return new IncidentAnalysisTask(id, incidentId, incidentVersion, status, result, failureCode,
                durationMs, reused, execution, attempts, modelCalls, queueDurationMs);
    }
}
