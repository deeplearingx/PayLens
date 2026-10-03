package cn.daxpay.open.paylens.analysis;

import java.time.OffsetDateTime;

public record DiagnosticEvidence(String id, String type, OffsetDateTime occurredAt, String summary) {
    public DiagnosticEvidence {
        summary = summary == null ? "信息缺失" : summary.substring(0, Math.min(summary.length(), 2000));
    }
}
