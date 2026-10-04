package com.github.guoyyit.dynamicmstjava.web;

import com.github.guoyyit.dynamicmstjava.model.ChangeOutcome;
import com.github.guoyyit.dynamicmstjava.model.EdgeChange;
import com.github.guoyyit.dynamicmstjava.model.MstResult;
import com.github.guoyyit.dynamicmstjava.service.GraphService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST 接口：变更提交、批量提交与最小生成森林查询。
 */
@RestController
@RequestMapping("/api/graph")
public class GraphController {

    private final GraphService service;

    public GraphController(GraphService service) {
        this.service = service;
    }

    @GetMapping("/mst")
    public MstResult mst() {
        return service.queryMst();
    }

    @PostMapping("/edges")
    public ChangeOutcome submit(@RequestBody ChangeRequest request) {
        return service.submit(toChange(request));
    }

    @PostMapping("/edges/batch")
    public ChangeOutcome batch(@RequestBody BatchChangeRequest request) {
        if (request == null || request.changes() == null) {
            throw new IllegalArgumentException("changes is required");
        }
        return service.submitBatch(request.changes().stream()
                .map(GraphController::toChange).toList());
    }

    private static EdgeChange toChange(ChangeRequest request) {
        if (request == null || request.type() == null || request.u() == null || request.v() == null) {
            throw new IllegalArgumentException("type, u, v are required");
        }
        return switch (request.type()) {
            case DELETE -> EdgeChange.delete(request.u(), request.v());
            case INSERT -> EdgeChange.insert(request.u(), request.v(), requireWeight(request));
            case UPDATE_WEIGHT ->
                    EdgeChange.updateWeight(request.u(), request.v(), requireWeight(request));
            case UPSERT -> EdgeChange.upsert(request.u(), request.v(), requireWeight(request));
        };
    }

    private static long requireWeight(ChangeRequest request) {
        if (request.weight() == null) {
            throw new IllegalArgumentException("weight is required for " + request.type());
        }
        return request.weight();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
    }
}
