package com.geico.poc.cassandrasql.postgres;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Decodes PostgreSQL wire protocol messages
 */
public class PostgresMessageDecoder extends ByteToMessageDecoder {
    
    private boolean startupReceived = false;
    private boolean sslHandled = false;
    
    // Protocol version codes
    private static final int SSL_REQUEST_CODE = 80877103;
    private static final int GSSAPI_REQUEST_CODE = 80877104;
    private static final int CANCEL_REQUEST_CODE = 80877102;
    
    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        // Handle startup message (no type byte)
        if (!startupReceived) {
            if (in.readableBytes() < 8) {
                return;
            }
            
            in.markReaderIndex();
            int length = in.readInt();
            
            if (in.readableBytes() < length - 4) {
                in.resetReaderIndex();
                return;
            }
            
            int protocolVersion = in.readInt();
            
            // Handle special startup requests
            if (protocolVersion == SSL_REQUEST_CODE) {
                // SSL not supported - send 'N'
                System.out.println("PostgreSQL client requested SSL - declining");
                ctx.writeAndFlush(ctx.alloc().buffer(1).writeByte('N'));
                return;
            }
            
            if (protocolVersion == GSSAPI_REQUEST_CODE) {
                // GSSAPI not supported - send 'N'
                System.out.println("PostgreSQL client requested GSSAPI - declining");
                ctx.writeAndFlush(ctx.alloc().buffer(1).writeByte('N'));
                return;
            }
            
            if (protocolVersion == CANCEL_REQUEST_CODE) {
                // Cancel request - ignore for MVP
                System.out.println("PostgreSQL client sent cancel request - ignoring");
                in.skipBytes(length - 8);
                return;
            }
            
            // Normal startup message
            // Read parameters
            Map<String, String> params = new HashMap<>();
            while (in.isReadable()) {
                String key = readCString(in);
                if (key.isEmpty()) break;
                String value = readCString(in);
                params.put(key, value);
            }
            
            String user = params.getOrDefault("user", "cassandra");
            String database = params.getOrDefault("database", "cassandra_sql");
            
            out.add(new PostgresMessage.StartupMessage(user, database));
            startupReceived = true;
            return;
        }
        
        // Handle regular messages (with type byte)
        if (in.readableBytes() < 5) {
            return;
        }
        
        in.markReaderIndex();
        
        byte type = in.readByte();
        int length = in.readInt();
        
        if (in.readableBytes() < length - 4) {
            in.resetReaderIndex();
            return;
        }
        
        switch (type) {
            case 'Q': // Query
                String sql = readCString(in);
                out.add(new PostgresMessage.QueryMessage(sql));
                break;
                
            case 'X': // Terminate
                out.add(new PostgresMessage.TerminateMessage());
                break;
                
            case 'P': // Parse (prepared statement)
                String stmtName = readCString(in);
                String query = readCString(in);
                int numParams = in.readShort();
                int[] paramTypes = new int[numParams];
                for (int i = 0; i < numParams; i++) {
                    paramTypes[i] = in.readInt();
                }
                out.add(new PostgresMessage.ParseMessage(stmtName, query, paramTypes));
                break;
                
            case 'B': // Bind
                String portalName = readCString(in);
                String statementName = readCString(in);
                
                // Read parameter format codes
                int numFormatCodes = in.readShort();
                for (int i = 0; i < numFormatCodes; i++) {
                    in.readShort(); // Skip format code (0=text, 1=binary)
                }
                
                // Read parameter values
                int numValues = in.readShort();
                java.util.List<String> paramValues = new java.util.ArrayList<>();
                for (int i = 0; i < numValues; i++) {
                    int valueLength = in.readInt();
                    if (valueLength == -1) {
                        paramValues.add(null); // NULL value
                    } else {
                        byte[] valueBytes = new byte[valueLength];
                        in.readBytes(valueBytes);
                        paramValues.add(new String(valueBytes, StandardCharsets.UTF_8));
                    }
                }
                
                // Read result format codes (skip)
                int numResultFormats = in.readShort();
                for (int i = 0; i < numResultFormats; i++) {
                    in.readShort();
                }
                
                out.add(new PostgresMessage.BindMessage(portalName, statementName, paramValues));
                break;
                
            case 'D': // Describe
                char descType = (char) in.readByte(); // 'S' or 'P'
                String descName = readCString(in);
                out.add(new PostgresMessage.DescribeMessage(descType, descName));
                break;
                
            case 'E': // Execute
                String execPortalName = readCString(in);
                int maxRows = in.readInt();
                out.add(new PostgresMessage.ExecuteMessage(execPortalName, maxRows));
                break;
                
            case 'S': // Sync
                out.add(new PostgresMessage.SyncMessage());
                break;
                
            case 'C': // Close
                char closeType = (char) in.readByte(); // 'S' or 'P'
                String closeName = readCString(in);
                out.add(new PostgresMessage.CloseMessage(closeType, closeName));
                break;
                
            case 'd': // CopyData
                byte[] copyData = new byte[length - 4];
                in.readBytes(copyData);
                out.add(new PostgresMessage.CopyDataMessage(copyData));
                break;
                
            case 'c': // CopyDone
                out.add(new PostgresMessage.CopyDoneMessage());
                break;
                
            case 'f': // CopyFail
                String errorMsg = readCString(in);
                out.add(new PostgresMessage.CopyFailMessage(errorMsg));
                break;
                
            default:
                // Skip unknown messages
                System.out.println("Unknown message type: " + (char)type + " (" + type + ")");
                in.skipBytes(length - 4);
                break;
        }
    }
    
    private String readCString(ByteBuf buf) {
        StringBuilder sb = new StringBuilder();
        while (buf.isReadable()) {
            byte b = buf.readByte();
            if (b == 0) break;
            sb.append((char) b);
        }
        return sb.toString();
    }
}

