package com.github.guoyyit.dynamicmstjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 服务可配置项。
 */
@ConfigurationProperties(prefix = "graph")
public class GraphProperties {

    private String dataDir = "data/graph";
    private int maxBatchSize = 1000;
    private long checkpointInterval = 100;

    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }

    public int getMaxBatchSize() {
        return maxBatchSize;
    }

    public void setMaxBatchSize(int maxBatchSize) {
        this.maxBatchSize = maxBatchSize;
    }

    public long getCheckpointInterval() {
        return checkpointInterval;
    }

    public void setCheckpointInterval(long checkpointInterval) {
        this.checkpointInterval = checkpointInterval;
    }
}
