package com.geico.poc.cassandrasql.storage;

import com.geico.poc.cassandrasql.config.CassandraSqlConfig;
import com.geico.poc.cassandrasql.storage.kv.KvStorageBackend;
import com.geico.poc.cassandrasql.storage.schema.SchemaStorageBackend;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Factory for creating the appropriate storage backend based on configuration.
 * 
 * This factory uses the Strategy pattern to select between different storage
 * implementations at runtime based on the configured storage mode.
 */
@Configuration
public class StorageBackendFactory {
    
    @Bean
    public StorageBackend storageBackend(
            CassandraSqlConfig config,
            KvStorageBackend kvBackend,
            SchemaStorageBackend schemaBackend) {
        
        StorageBackend backend;
        if (config.getStorageMode() == CassandraSqlConfig.StorageMode.KV) {
            System.out.println("🔑 Configuring KV Storage Backend");
            backend = kvBackend;
        } else {
            System.out.println("📊 Configuring Schema Storage Backend");
            backend = schemaBackend;
        }
        
        backend.initialize();
        return backend;
    }
}

