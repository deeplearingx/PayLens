package cn.daxpay.open.paylens.analysis;

import java.util.List;

public record IncidentAnalysisTask(String id, String incidentId, int incidentVersion, String status,
                                   IncidentAnalysisResult result, String failureCode, Long durationMs,
                                   boolean reused, IncidentAnalysisExecution execution,
                                   List<IncidentAnalysisAttempt> attempts) {
    public IncidentAnalysisTask {
        attempts = attempts == null ? List.of() : List.copyOf(attempts);
    }
    public IncidentAnalysisTask withReused(boolean reused) {
        return new IncidentAnalysisTask(id, incidentId, incidentVersion, status, result, failureCode,
                durationMs, reused, execution, attempts);
    }
}
