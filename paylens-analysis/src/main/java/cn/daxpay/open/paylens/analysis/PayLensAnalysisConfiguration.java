package cn.daxpay.open.paylens.analysis;

import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@ConditionalOnProperty(prefix = "paylens.analysis", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(PayLensAiProperties.class)
@EnableScheduling
public class PayLensAnalysisConfiguration {
    @Bean("payLensAnalysisExecutor")
    public ThreadPoolTaskExecutor payLensAnalysisExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("paylens-analysis-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);
        return executor;
    }

    @Bean
    @ConditionalOnProperty(prefix = "paylens.ai", name = "mode", havingValue = "mock", matchIfMissing = true)
    public DiagnosticModel mockDiagnosticModel(ObjectMapper mapper) { return new MockDiagnosticModel(mapper); }

    @Bean
    @ConditionalOnProperty(prefix = "paylens.ai", name = "mode", havingValue = "openai")
    public DiagnosticModel openAiCompatibleDiagnosticModel(PayLensAiProperties properties, MeterRegistry meterRegistry) {
        return new OpenAiCompatibleDiagnosticModel(properties, meterRegistry);
    }
}
