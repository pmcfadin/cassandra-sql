package com.geico.poc.cassandrasql.postgres;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

import java.nio.charset.StandardCharsets;

/**
 * Encodes PostgreSQL wire protocol messages
 */
public class PostgresMessageEncoder extends MessageToByteEncoder<PostgresMessage> {
    
    @Override
    protected void encode(ChannelHandlerContext ctx, PostgresMessage msg, ByteBuf out) {
        if (msg instanceof PostgresMessage.AuthenticationOkMessage) {
            encodeAuthenticationOk(out);
        } else if (msg instanceof PostgresMessage.ParameterStatusMessage) {
            encodeParameterStatus(out, (PostgresMessage.ParameterStatusMessage) msg);
        } else if (msg instanceof PostgresMessage.ReadyForQueryMessage) {
            encodeReadyForQuery(out, (PostgresMessage.ReadyForQueryMessage) msg);
        } else if (msg instanceof PostgresMessage.RowDescriptionMessage) {
            encodeRowDescription(out, (PostgresMessage.RowDescriptionMessage) msg);
        } else if (msg instanceof PostgresMessage.DataRowMessage) {
            encodeDataRow(out, (PostgresMessage.DataRowMessage) msg);
        } else if (msg instanceof PostgresMessage.CommandCompleteMessage) {
            encodeCommandComplete(out, (PostgresMessage.CommandCompleteMessage) msg);
        } else if (msg instanceof PostgresMessage.ErrorResponseMessage) {
            encodeErrorResponse(out, (PostgresMessage.ErrorResponseMessage) msg);
        } else if (msg instanceof PostgresMessage.NoticeResponseMessage) {
            encodeNoticeResponse(out, (PostgresMessage.NoticeResponseMessage) msg);
        } else if (msg instanceof PostgresMessage.ParseCompleteMessage) {
            encodeParseComplete(out);
        } else if (msg instanceof PostgresMessage.BindCompleteMessage) {
            encodeBindComplete(out);
        } else if (msg instanceof PostgresMessage.CloseCompleteMessage) {
            encodeCloseComplete(out);
        } else if (msg instanceof PostgresMessage.NoDataMessage) {
            encodeNoData(out);
        } else if (msg instanceof PostgresMessage.ParameterDescriptionMessage) {
            encodeParameterDescription(out, (PostgresMessage.ParameterDescriptionMessage) msg);
        } else if (msg instanceof PostgresMessage.CopyInResponseMessage) {
            encodeCopyInResponse(out, (PostgresMessage.CopyInResponseMessage) msg);
        } else if (msg instanceof PostgresMessage.CopyDataMessage) {
            encodeCopyData(out, (PostgresMessage.CopyDataMessage) msg);
        } else if (msg instanceof PostgresMessage.CopyDoneMessage) {
            encodeCopyDone(out);
        }
    }
    
    private void encodeAuthenticationOk(ByteBuf out) {
        out.writeByte('R');
        out.writeInt(8);
        out.writeInt(0); // AuthenticationOk
    }
    
    private void encodeParameterStatus(ByteBuf out, PostgresMessage.ParameterStatusMessage msg) {
        ByteBuf temp = Unpooled.buffer();
        writeCString(temp, msg.getName());
        writeCString(temp, msg.getValue());
        
        out.writeByte('S');
        out.writeInt(4 + temp.readableBytes());
        out.writeBytes(temp);
        temp.release();
    }
    
    private void encodeReadyForQuery(ByteBuf out, PostgresMessage.ReadyForQueryMessage msg) {
        out.writeByte('Z');
        out.writeInt(5);
        out.writeByte(msg.getStatus());
    }
    
    private void encodeRowDescription(ByteBuf out, PostgresMessage.RowDescriptionMessage msg) {
        ByteBuf temp = Unpooled.buffer();
        
        temp.writeShort(msg.getColumns().size());
        
        for (String column : msg.getColumns()) {
            writeCString(temp, column);
            temp.writeInt(0);      // Table OID
            temp.writeShort(0);    // Column number
            temp.writeInt(25);     // Type OID (25 = TEXT)
            temp.writeShort(-1);   // Type size
            temp.writeInt(-1);     // Type modifier
            temp.writeShort(0);    // Format code (0 = text)
        }
        
        out.writeByte('T');
        out.writeInt(4 + temp.readableBytes());
        out.writeBytes(temp);
        temp.release();
    }
    
    private void encodeDataRow(ByteBuf out, PostgresMessage.DataRowMessage msg) {
        ByteBuf temp = Unpooled.buffer();
        
        temp.writeShort(msg.getValues().size());
        
        for (Object value : msg.getValues()) {
            if (value == null) {
                temp.writeInt(-1);
            } else {
                String strValue = value.toString();
                byte[] bytes = strValue.getBytes(StandardCharsets.UTF_8);
                temp.writeInt(bytes.length);
                temp.writeBytes(bytes);
            }
        }
        
        out.writeByte('D');
        out.writeInt(4 + temp.readableBytes());
        out.writeBytes(temp);
        temp.release();
    }
    
    private void encodeCommandComplete(ByteBuf out, PostgresMessage.CommandCompleteMessage msg) {
        byte[] tag = msg.getTag().getBytes(StandardCharsets.UTF_8);
        out.writeByte('C');
        out.writeInt(4 + tag.length + 1);
        out.writeBytes(tag);
        out.writeByte(0);
    }
    
    private void encodeErrorResponse(ByteBuf out, PostgresMessage.ErrorResponseMessage msg) {
        ByteBuf temp = Unpooled.buffer();
        
        temp.writeByte('S');
        writeCString(temp, "ERROR");
        
        temp.writeByte('M');
        writeCString(temp, msg.getMessage());
        
        temp.writeByte(0);
        
        out.writeByte('E');
        out.writeInt(4 + temp.readableBytes());
        out.writeBytes(temp);
        temp.release();
    }
    
    private void encodeNoticeResponse(ByteBuf out, PostgresMessage.NoticeResponseMessage msg) {
        ByteBuf temp = Unpooled.buffer();
        
        temp.writeByte('S');
        writeCString(temp, "NOTICE");
        
        temp.writeByte('M');
        writeCString(temp, msg.getMessage());
        
        temp.writeByte(0);
        
        out.writeByte('N');  // Notice uses 'N' instead of 'E'
        out.writeInt(4 + temp.readableBytes());
        out.writeBytes(temp);
        temp.release();
    }
    
    private void encodeParseComplete(ByteBuf out) {
        out.writeByte('1'); // ParseComplete
        out.writeInt(4);
    }
    
    private void encodeBindComplete(ByteBuf out) {
        out.writeByte('2'); // BindComplete
        out.writeInt(4);
    }
    
    private void encodeCloseComplete(ByteBuf out) {
        out.writeByte('3'); // CloseComplete
        out.writeInt(4);
    }
    
    private void encodeNoData(ByteBuf out) {
        out.writeByte('n'); // NoData
        out.writeInt(4);
    }
    
    private void encodeParameterDescription(ByteBuf out, PostgresMessage.ParameterDescriptionMessage msg) {
        ByteBuf temp = Unpooled.buffer();
        
        int[] paramTypes = msg.getParamTypes();
        temp.writeShort(paramTypes.length);
        for (int paramType : paramTypes) {
            temp.writeInt(paramType);
        }
        
        out.writeByte('t'); // ParameterDescription
        out.writeInt(4 + temp.readableBytes());
        out.writeBytes(temp);
        temp.release();
    }
    
    private void encodeCopyInResponse(ByteBuf out, PostgresMessage.CopyInResponseMessage msg) {
        ByteBuf temp = Unpooled.buffer();
        
        temp.writeByte(msg.getFormat()); // 0 = text, 1 = binary
        temp.writeShort(msg.getNumColumns()); // Number of columns
        
        // Format codes for each column (0 = text)
        for (int i = 0; i < msg.getNumColumns(); i++) {
            temp.writeShort(0); // Text format for all columns
        }
        
        out.writeByte('G'); // CopyInResponse
        out.writeInt(4 + temp.readableBytes());
        out.writeBytes(temp);
        temp.release();
    }
    
    private void encodeCopyData(ByteBuf out, PostgresMessage.CopyDataMessage msg) {
        byte[] data = msg.getData();
        out.writeByte('d'); // CopyData
        out.writeInt(4 + data.length);
        out.writeBytes(data);
    }
    
    private void encodeCopyDone(ByteBuf out) {
        out.writeByte('c'); // CopyDone
        out.writeInt(4);
    }
    
    private void writeCString(ByteBuf buf, String str) {
        buf.writeBytes(str.getBytes(StandardCharsets.UTF_8));
        buf.writeByte(0);
    }
}





