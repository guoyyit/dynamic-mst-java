package com.github.guoyyit.dynamicmstjava.api;

import com.github.guoyyit.dynamicmstjava.graph.Batch;
import com.github.guoyyit.dynamicmstjava.graph.Change;
import com.github.guoyyit.dynamicmstjava.graph.Edge;
import com.github.guoyyit.dynamicmstjava.graph.GraphService;
import com.github.guoyyit.dynamicmstjava.graph.MstResult;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * REST API for the graph data service.
 */
@RestController
@RequestMapping("/api/graph")
public class GraphController {

    private final GraphService graphService;

    public GraphController(GraphService graphService) {
        this.graphService = graphService;
    }

    /**
     * Commits an atomic batch of changes.
     */
    @PostMapping("/batch")
    public void commit(@RequestBody BatchRequest request) {
        if (request == null || request.changes() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "changes must not be null");
        }
        graphService.commit(new Batch(request.changes()));
    }

    /**
     * Returns the minimum spanning forest.
     */
    @GetMapping("/mst")
    public MstResult mst() {
        return graphService.query();
    }

    /**
     * Returns all current edges.
     */
    @GetMapping("/edges")
    public List<Edge> edges() {
        return graphService.edges();
    }

    /**
     * Request body for a batch commit.
     */
    public record BatchRequest(List<Change> changes) {
    }

    @ExceptionHandler(UnsupportedOperationException.class)
    public void handleUnsupported(UnsupportedOperationException e) {
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, e.getMessage());
    }
}
