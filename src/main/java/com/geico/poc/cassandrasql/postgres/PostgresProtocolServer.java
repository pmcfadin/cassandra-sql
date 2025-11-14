package com.geico.poc.cassandrasql.postgres;

import com.datastax.oss.driver.api.core.CqlSession;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import com.geico.poc.cassandrasql.QueryService;
import com.geico.poc.cassandrasql.config.CassandraSqlConfig;
import com.geico.poc.cassandrasql.transaction.TransactionSessionManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * PostgreSQL wire protocol server
 */
@Component
public class PostgresProtocolServer {
    
    private static final int POSTGRES_PORT = 5432;
    
    @Autowired
    private QueryService queryService;
    
    @Autowired
    private CqlSession session;
    
    @Autowired
    private TransactionSessionManager transactionManager;
    
    @Autowired
    private ConnectionLimiter connectionLimiter;
    
    @Autowired
    private CassandraSqlConfig config;
    
    private final PreparedStatementManager preparedStatementManager = new PreparedStatementManager();
    
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    
    @PostConstruct
    public void start() {
        // Start server in background thread
        new Thread(() -> {
            try {
                startServer();
            } catch (Exception e) {
                System.err.println("Failed to start PostgreSQL protocol server: " + e.getMessage());
                e.printStackTrace();
            }
        }, "postgres-server").start();
    }
    
    private void startServer() throws Exception {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        
        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        // Check connection limit BEFORE creating handler
                        if (!connectionLimiter.tryAcquire()) {
                            System.err.println("❌ Connection rejected: limit reached");
                            ch.close();
                            return;
                        }
                        
                        // Log connection stats periodically
                        if (connectionLimiter.isNearCapacity()) {
                            System.out.println("⚠️  " + connectionLimiter.getStats());
                        }
                        
                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast(new PostgresMessageDecoder());
                        pipeline.addLast(new PostgresMessageEncoder());
                        pipeline.addLast(new PostgresConnectionHandler(
                            queryService, session, transactionManager, 
                            preparedStatementManager, connectionLimiter, config));
                    }
                });
            
            ChannelFuture future = bootstrap.bind(POSTGRES_PORT).sync();
            serverChannel = future.channel();
            
            System.out.println("✅ PostgreSQL protocol server started on port " + POSTGRES_PORT);
            System.out.println("   Connect with: psql -h localhost -p " + POSTGRES_PORT + " -U cassandra -d cassandra_sql");
            
            serverChannel.closeFuture().sync();
            
        } finally {
            workerGroup.shutdownGracefully();
            bossGroup.shutdownGracefully();
        }
    }
    
    @PreDestroy
    public void stop() {
        System.out.println("Stopping PostgreSQL protocol server...");
        
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
    }
}

