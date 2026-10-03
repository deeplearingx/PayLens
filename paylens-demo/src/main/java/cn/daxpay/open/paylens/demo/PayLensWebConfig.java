package cn.daxpay.open.paylens.demo;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@ConditionalOnProperty(prefix = "paylens.demo", name = "enabled", havingValue = "true")
public class PayLensWebConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Read the client identity from the authenticated session, never from x-client-code.
        registry.addInterceptor(new PayLensAdminClientInterceptor())
                .addPathPatterns("/paylens", "/paylens/**")
                .order(1);
    }
}
