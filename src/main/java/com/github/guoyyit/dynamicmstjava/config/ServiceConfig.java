package com.github.guoyyit.dynamicmstjava.config;

import com.github.guoyyit.dynamicmstjava.service.GraphService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring 装配：以单例 Bean 暴露 GraphService。
 */
@Configuration
public class ServiceConfig {

    @Bean
    public GraphService graphService(GraphProperties properties) {
        return new GraphService(properties.getDataDir(),
                properties.getMaxBatchSize(), properties.getCheckpointInterval());
    }
}
