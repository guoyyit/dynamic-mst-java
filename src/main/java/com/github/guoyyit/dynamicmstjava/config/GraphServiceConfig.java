package com.github.guoyyit.dynamicmstjava.config;

import com.github.guoyyit.dynamicmstjava.graph.GraphService;
import com.github.guoyyit.dynamicmstjava.persistence.Recovery;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Wires up the {@link GraphService} with crash recovery.
 */
@Configuration
public class GraphServiceConfig {

    @Bean
    public GraphService graphService(@Value("${graph.data-dir:./data}") String dataDir) throws IOException {
        return Recovery.recover(Path.of(dataDir));
    }
}
