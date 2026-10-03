package cn.daxpay.open.paylens.analysis;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "paylens.embedding")
public class PayLensEmbeddingProperties {
    private boolean enabled = true;
    private String modelDirectory = "models/bge-small-zh-v1.5";
    private int maxBatchSize = 16;
    private int maxSequenceLength = 512;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getModelDirectory() { return modelDirectory; }
    public void setModelDirectory(String modelDirectory) { this.modelDirectory = modelDirectory; }
    public int getMaxBatchSize() { return maxBatchSize; }
    public void setMaxBatchSize(int maxBatchSize) { this.maxBatchSize = maxBatchSize; }
    public int getMaxSequenceLength() { return maxSequenceLength; }
    public void setMaxSequenceLength(int maxSequenceLength) { this.maxSequenceLength = maxSequenceLength; }
}
