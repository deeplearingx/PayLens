package cn.daxpay.open.paylens.analysis;

import java.time.OffsetDateTime;

public record IncidentAnalysisAttempt(int attemptNo, String status, OffsetDateTime startedAt,
                                     OffsetDateTime completedAt, Long durationMs, String failureCode,
                                     String modelId, Long promptTokens, Long completionTokens) {}
