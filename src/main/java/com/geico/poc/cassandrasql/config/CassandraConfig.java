package com.geico.poc.cassandrasql.config;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.CqlSessionBuilder;
import com.datastax.oss.driver.api.core.DefaultConsistencyLevel;
import com.datastax.oss.driver.api.core.config.DefaultDriverOption;
import com.datastax.oss.driver.api.core.config.DriverConfigLoader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.InetSocketAddress;
import java.time.Duration;

/**
 * Cassandra configuration
 * Configures the CQL session with proper datacenter settings
 */
@Configuration
public class CassandraConfig {
    
    @Value("${cassandra.contact-points:localhost}")
    private String contactPoints;
    
    @Value("${cassandra.port:9042}")
    private int port;
    
    @Value("${cassandra.local-datacenter:datacenter1}")
    private String localDatacenter;
    
    @Value("${cassandra.keyspace:cassandra_sql}")
    private String keyspace;
    
    @Bean
    public CqlSession cassandraSession() {
        System.out.println("🔧 Configuring Cassandra session:");
        System.out.println("   Contact points: " + contactPoints);
        System.out.println("   Port: " + port);
        System.out.println("   Local datacenter: " + localDatacenter);
        System.out.println("   Keyspace: " + keyspace);
        
        // Configure driver for Accord transactions
        // Accord uses QUORUM consistency - transactions are handled via BEGIN TRANSACTION syntax
        // No need for SERIAL consistency or conditional updates (IF NOT EXISTS)
        DriverConfigLoader loader = DriverConfigLoader.programmaticBuilder()
            .withString(DefaultDriverOption.REQUEST_CONSISTENCY, "QUORUM")
            .withDuration(DefaultDriverOption.REQUEST_TIMEOUT, Duration.ofSeconds(10))
            .build();
        
        // First, connect without a keyspace to create it if needed
        CqlSessionBuilder builder = CqlSession.builder()
            .addContactPoint(new InetSocketAddress(contactPoints, port))
            .withLocalDatacenter(localDatacenter)
            .withConfigLoader(loader);
        
        try {
            CqlSession session = builder.build();
            System.out.println("✅ Cassandra session created successfully");
            System.out.println("   Consistency level: QUORUM (Accord transactions via BEGIN TRANSACTION)");
            
            // Create keyspace if it doesn't exist
            try {
                System.out.println("🔍 Checking if keyspace '" + keyspace + "' exists...");
                session.execute(String.format(
                    "CREATE KEYSPACE IF NOT EXISTS %s WITH replication = " +
                    "{'class': 'SimpleStrategy', 'replication_factor': 1}", keyspace));
                System.out.println("✅ Keyspace '" + keyspace + "' ready");
                
                // Switch to the keyspace
                session.execute("USE " + keyspace);
                System.out.println("✅ Using keyspace: " + keyspace);
            } catch (Exception e) {
                System.err.println("⚠️  Warning: Could not create/use keyspace: " + e.getMessage());
            }
            
            return session;
        } catch (Exception e) {
            System.err.println("❌ Failed to create Cassandra session: " + e.getMessage());
            System.err.println("💡 Make sure Cassandra is running on " + contactPoints + ":" + port);
            throw e;
        }
    }
}



