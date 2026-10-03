package cn.daxpay.open.paylens.analysis;

import java.time.OffsetDateTime;

public record AnalysisAttempt(int attemptNo, String status, OffsetDateTime startedAt,
                              OffsetDateTime completedAt, Long durationMs, String failureCode,
                              String modelId, String returnedModelId, Long promptTokens, Long completionTokens,
                              String promptVersion, String knowledgeVersion) {}
