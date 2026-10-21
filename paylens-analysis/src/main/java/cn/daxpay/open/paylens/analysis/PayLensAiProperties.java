package cn.daxpay.open.paylens.analysis;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "paylens.ai")
public class PayLensAiProperties {
    private String mode = "mock";
    private String baseUrl = "https://api.openai.com/v1";
    private String apiKey = "";
    private String model = "";
    private int connectTimeoutSeconds = 5;
    private int requestTimeoutSeconds = 60;

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public int getConnectTimeoutSeconds() { return connectTimeoutSeconds; }
    public void setConnectTimeoutSeconds(int connectTimeoutSeconds) { this.connectTimeoutSeconds = connectTimeoutSeconds; }
    public int getRequestTimeoutSeconds() { return requestTimeoutSeconds; }
    public void setRequestTimeoutSeconds(int requestTimeoutSeconds) { this.requestTimeoutSeconds = requestTimeoutSeconds; }
}
