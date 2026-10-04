package com.github.guoyyit.dynamicmstjava.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class GraphControllerTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) {
        registry.add("graph.data-dir", () -> tempDir.resolve("web").toString());
    }

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void submitBatchQueryAndRejectInvalid() throws Exception {
        String batch = mapper.writeValueAsString(Map.of("changes", List.of(
                Map.of("type", "UPSERT", "u", 1, "v", 2, "weight", 3),
                Map.of("type", "UPSERT", "u", 2, "v", 3, "weight", 1),
                Map.of("type", "UPSERT", "u", 1, "v", 3, "weight", 5))));
        mockMvc.perform(post("/api/graph/edges/batch")
                        .contentType(MediaType.APPLICATION_JSON).content(batch))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    JsonNode node = mapper.readTree(result.getResponse().getContentAsString());
                    assertEquals(1, node.get("version").asLong());
                    assertEquals(3, node.get("results").size());
                });

        mockMvc.perform(get("/api/graph/mst"))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    JsonNode node = mapper.readTree(result.getResponse().getContentAsString());
                    assertEquals(4, node.get("totalWeight").asLong());
                    assertEquals(2, node.get("edgeCount").asInt());
                    assertEquals(1, node.get("componentCount").asInt());
                });

        String invalid = mapper.writeValueAsString(
                Map.of("type", "UPSERT", "u", 1, "v", 2));
        mockMvc.perform(post("/api/graph/edges")
                        .contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isBadRequest());
    }
}
