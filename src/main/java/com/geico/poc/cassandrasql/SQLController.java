package com.geico.poc.cassandrasql;

import com.geico.poc.cassandrasql.dto.QueryRequest;
import com.geico.poc.cassandrasql.dto.QueryResponse;
import com.geico.poc.cassandrasql.kv.LockDiagnostics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/sql")
public class SQLController {

    @Autowired
    private QueryService queryService;
    
    @Autowired(required = false)
    private LockDiagnostics lockDiagnostics;

    @PostMapping("/execute")
    public ResponseEntity<QueryResponse> executeQuery(@RequestBody QueryRequest request) {
        try {
            QueryResponse response = queryService.execute(request.getSql());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.badRequest()
                .body(QueryResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }
    
    @GetMapping("/admin/locks/dump")
    public ResponseEntity<String> dumpLocks() {
        try {
            if (lockDiagnostics != null) {
                lockDiagnostics.dumpAllLocks();
                return ResponseEntity.ok("Lock dump written to logs");
            } else {
                return ResponseEntity.ok("Lock diagnostics not available (not in KV mode)");
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error: " + e.getMessage());
        }
    }
}







