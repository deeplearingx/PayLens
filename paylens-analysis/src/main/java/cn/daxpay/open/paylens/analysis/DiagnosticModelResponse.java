package cn.daxpay.open.paylens.analysis;

public record DiagnosticModelResponse(String content, Long promptTokens, Long completionTokens, String modelId) {}
