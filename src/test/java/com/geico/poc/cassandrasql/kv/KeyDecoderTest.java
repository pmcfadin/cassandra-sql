package com.geico.poc.cassandrasql.kv;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for decoding KV store keys.
 * Useful for debugging and understanding key structure.
 */
public class KeyDecoderTest {
    
    @Test
    public void testDecodeSequenceKey() {
        // Example from logs: 0x0500000000000000030000000000000000726f7769645f736571
        byte[] key = hexToBytes("0500000000000000030000000000000000726f7769645f736571");
        
        KeyDecoder decoder = new KeyDecoder(key);
        
        assertEquals(KeyEncoder.NAMESPACE_SEQUENCE, decoder.getNamespace());
        assertEquals(3L, decoder.getTableId());
        assertEquals("rowid_seq", decoder.getSequenceName());
        
        System.out.println("✅ Decoded Sequence Key:");
        System.out.println("   Namespace: SEQUENCE (0x05)");
        System.out.println("   Table ID: 3");
        System.out.println("   Sequence Name: rowid_seq");
    }
    
    @Test
    public void testDecodeTableDataKey() {
        // Create a table data key: namespace(1) + tableId(8) + indexId(8) + pkValue + timestamp
        ByteBuffer buffer = ByteBuffer.allocate(100);
        buffer.put(KeyEncoder.NAMESPACE_TABLE_DATA);
        buffer.putLong(42L); // table ID
        buffer.putLong(0L);  // PRIMARY_INDEX_ID
        buffer.putInt(123);  // PK value
        buffer.putLong(1234567890000000L); // timestamp
        
        byte[] key = new byte[buffer.position()];
        buffer.rewind();
        buffer.get(key);
        
        KeyDecoder decoder = new KeyDecoder(key);
        
        assertEquals(KeyEncoder.NAMESPACE_TABLE_DATA, decoder.getNamespace());
        assertEquals(42L, decoder.getTableId());
        assertEquals(0L, decoder.getIndexId());
        
        System.out.println("✅ Decoded Table Data Key:");
        System.out.println("   Namespace: TABLE_DATA (0x01)");
        System.out.println("   Table ID: 42");
        System.out.println("   Index ID: 0 (PRIMARY)");
    }
    
    @Test
    public void testDecodeSchemaKey() {
        // Schema key: namespace(1) + tableId(8) + reserved(8) + "schema"
        byte[] key = KeyEncoder.encodeSchemaKey(100L, "schema");
        
        KeyDecoder decoder = new KeyDecoder(key);
        
        assertEquals(KeyEncoder.NAMESPACE_SCHEMA, decoder.getNamespace());
        assertEquals(100L, decoder.getTableId());
        assertEquals("schema", decoder.getMetadataType());
        
        System.out.println("✅ Decoded Schema Key:");
        System.out.println("   Namespace: SCHEMA (0x02)");
        System.out.println("   Table ID: 100");
        System.out.println("   Metadata Type: schema");
    }
    
    @Test
    public void testDecodeTransactionKey() {
        UUID txId = UUID.randomUUID();
        
        ByteBuffer buffer = ByteBuffer.allocate(17);
        buffer.put(KeyEncoder.NAMESPACE_TRANSACTION);
        buffer.putLong(txId.getMostSignificantBits());
        buffer.putLong(txId.getLeastSignificantBits());
        
        byte[] key = new byte[buffer.position()];
        buffer.rewind();
        buffer.get(key);
        
        KeyDecoder decoder = new KeyDecoder(key);
        
        assertEquals(KeyEncoder.NAMESPACE_TRANSACTION, decoder.getNamespace());
        assertEquals(txId, decoder.getTransactionId());
        
        System.out.println("✅ Decoded Transaction Key:");
        System.out.println("   Namespace: TRANSACTION (0x04)");
        System.out.println("   Transaction ID: " + txId);
    }
    
    @Test
    public void testPrintAllNamespaces() {
        System.out.println("\n📚 Namespace Reference:");
        System.out.println("   0x01 = TABLE_DATA    (actual row data)");
        System.out.println("   0x02 = SCHEMA        (table metadata)");
        System.out.println("   0x03 = INDEX         (secondary index entries)");
        System.out.println("   0x04 = TRANSACTION   (transaction metadata)");
        System.out.println("   0x05 = SEQUENCE      (sequence values)");
        System.out.println("   0x06 = LOCK          (transaction locks)");
        System.out.println("   0x07 = WRITE         (buffered writes)");
    }
    
    // Helper methods
    
    private byte[] hexToBytes(String hex) {
        hex = hex.replaceAll("0x", "").replaceAll("0X", "");
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                                 + Character.digit(hex.charAt(i+1), 16));
        }
        return data;
    }
    
    /**
     * Simple key decoder utility class
     */
    static class KeyDecoder {
        private final byte[] key;
        private final byte namespace;
        
        public KeyDecoder(byte[] key) {
            this.key = key;
            this.namespace = key[0];
        }
        
        public byte getNamespace() {
            return namespace;
        }
        
        public long getTableId() {
            if (key.length < 9) return -1;
            ByteBuffer buffer = ByteBuffer.wrap(key, 1, 8);
            return buffer.getLong();
        }
        
        public long getIndexId() {
            if (key.length < 17) return -1;
            ByteBuffer buffer = ByteBuffer.wrap(key, 9, 8);
            return buffer.getLong();
        }
        
        public String getSequenceName() {
            if (namespace != KeyEncoder.NAMESPACE_SEQUENCE) return null;
            if (key.length < 18) return null;
            
            // Skip namespace(1) + tableId(8) + reserved(8) = 17 bytes
            byte[] nameBytes = new byte[key.length - 17];
            System.arraycopy(key, 17, nameBytes, 0, nameBytes.length);
            return new String(nameBytes, StandardCharsets.UTF_8);
        }
        
        public String getMetadataType() {
            if (namespace != KeyEncoder.NAMESPACE_SCHEMA) return null;
            if (key.length < 18) return null;
            
            // Skip namespace(1) + tableId(8) + reserved(8) = 17 bytes
            byte[] typeBytes = new byte[key.length - 17];
            System.arraycopy(key, 17, typeBytes, 0, typeBytes.length);
            return new String(typeBytes, StandardCharsets.UTF_8);
        }
        
        public UUID getTransactionId() {
            if (namespace != KeyEncoder.NAMESPACE_TRANSACTION) return null;
            if (key.length < 17) return null;
            
            ByteBuffer buffer = ByteBuffer.wrap(key, 1, 16);
            long mostSig = buffer.getLong();
            long leastSig = buffer.getLong();
            return new UUID(mostSig, leastSig);
        }
        
        public String toHexString() {
            StringBuilder sb = new StringBuilder();
            for (byte b : key) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        }
    }
}


