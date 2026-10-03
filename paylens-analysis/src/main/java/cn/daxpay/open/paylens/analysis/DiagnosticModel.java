package cn.daxpay.open.paylens.analysis;

public interface DiagnosticModel {
    String analyze(String prompt) throws Exception;
    String modelId();

    default DiagnosticModelResponse analyzeDetailed(String prompt) throws Exception {
        return new DiagnosticModelResponse(analyze(prompt), null, null, modelId());
    }

    /** Stable, non-secret identifier for the settings that affect model output. */
    default String configurationId() { return modelId(); }
}
