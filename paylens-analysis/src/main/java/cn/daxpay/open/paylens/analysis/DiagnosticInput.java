package cn.daxpay.open.paylens.analysis;

import java.util.List;

public record DiagnosticInput(String tradeNo, String localStatus, String channelStatus,
                              String abnormalType, List<DiagnosticEvidence> evidence) {
    public DiagnosticInput {
        evidence = List.copyOf(evidence);
    }
}
