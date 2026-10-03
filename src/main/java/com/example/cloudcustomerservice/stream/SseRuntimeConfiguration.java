package com.example.cloudcustomerservice.stream;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.*;
import reactor.core.scheduler.*;

/** 保持 Servlet / MVC：网络写入和阻塞 JDBC 使用不同的有界执行资源。 */
@Configuration @Profile("local & knowledge") @EnableConfigurationProperties(SseSettings.class)
public class SseRuntimeConfiguration {
    @Bean("sseMvcExecutor") public ThreadPoolTaskExecutor sseMvcExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4); executor.setMaxPoolSize(16); executor.setQueueCapacity(128);
        executor.setThreadNamePrefix("sse-write-"); return executor;
    }
    /** 流本身先结束；MVC 的五分钟超时用于兜底，不能代替模型的空闲及总时限。 */
    @Bean public WebMvcConfigurer sseMvcConfigurer(@Qualifier("sseMvcExecutor") ThreadPoolTaskExecutor executor) {
        return new WebMvcConfigurer() {
            @Override public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
                configurer.setTaskExecutor(executor); configurer.setDefaultTimeout(300_000L);
            }
        };
    }
    @Bean(name="sseReadScheduler", destroyMethod="dispose") public Scheduler sseReadScheduler() {
        return Schedulers.newBoundedElastic(8, 100, "sse-db");
    }
}
