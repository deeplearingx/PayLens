package cn.daxpay.open.paylens.analysis;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@ConditionalOnProperty(prefix = "paylens.embedding", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(PayLensEmbeddingProperties.class)
public class PayLensEmbeddingConfiguration {
    @Bean("payLensEmbeddingExecutor")
    public ThreadPoolTaskExecutor payLensEmbeddingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.setThreadNamePrefix("paylens-embedding-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);
        return executor;
    }

    @Bean(destroyMethod = "close")
    public BgeSmallZhOnnxEmbeddingModel bgeSmallZhOnnxEmbeddingModel(
            PayLensEmbeddingProperties properties,
            @Qualifier("payLensEmbeddingExecutor") ThreadPoolTaskExecutor initializer) {
        BgeSmallZhOnnxEmbeddingModel model = new BgeSmallZhOnnxEmbeddingModel(properties, initializer);
        model.initializeAsync();
        return model;
    }
}
