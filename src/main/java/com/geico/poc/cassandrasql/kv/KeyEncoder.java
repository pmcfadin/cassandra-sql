package com.geico.poc.cassandrasql.kv;

import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Encodes and decodes keys for key-value storage mode.
 * 
 * Key format: [Namespace:1][TableID:8][IndexID:8][EncodedKey:*][Timestamp:8]
 * 
 * All multi-byte integers are big-endian to preserve sort order.
 */
@Component
public class KeyEncoder {
    
    // Namespace prefixes
    public static final byte NAMESPACE_TABLE_DATA = 0x01;
    public static final byte NAMESPACE_INDEX_DATA = 0x02;
    public static final byte NAMESPACE_SCHEMA = 0x03;
    public static final byte NAMESPACE_TRANSACTION = 0x04;
    public static final byte NAMESPACE_SEQUENCE = 0x05;
    public static final byte NAMESPACE_ENUM = 0x06;
    
    // Special values
    public static final long PRIMARY_INDEX_ID = 0L;
    public static final long MAX_TIMESTAMP = 0xFFFFFFFFFFFFFFFFL;
    
    // Type prefixes for value encoding (ensures consistent sort order across types)
    private static final byte TYPE_NULL = 0x00;
    private static final byte TYPE_INT = 0x01;
    private static final byte TYPE_LONG = 0x02;
    private static final byte TYPE_STRING = 0x03;
    private static final byte TYPE_BOOLEAN = 0x04;
    private static final byte TYPE_DOUBLE = 0x05;
    private static final byte TYPE_FLOAT = 0x06;
    private static final byte TYPE_BYTES = 0x07;
    
    /**
     * Encode a table data key (primary key)
     */
    public static byte[] encodeTableDataKey(long tableId, List<Object> pkValues, long timestamp) {
        ByteBuffer buffer = ByteBuffer.allocate(1024); // Initial capacity
        
        buffer.put(NAMESPACE_TABLE_DATA);
        buffer.putLong(tableId);
        buffer.putLong(PRIMARY_INDEX_ID);
        
        // Encode primary key values
        for (Object value : pkValues) {
            byte[] encoded = encodeValue(value);
            buffer.put(encoded);
        }
        
        // NOTE: Timestamp is NOT encoded in the key - it's stored as a separate column (ts)
        // This allows multiple MVCC versions of the same logical row to share the same key
        // The timestamp is passed as a parameter for API consistency but not used in key encoding
        
        // Return only the used portion
        byte[] result = new byte[buffer.position()];
        buffer.rewind();
        buffer.get(result);
        return result;
    }
    
    /**
     * Encode an index key
     */
    public static byte[] encodeIndexKey(long tableId, long indexId, List<Object> indexValues, 
                                       List<Object> pkValues, long timestamp) {
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        
        buffer.put(NAMESPACE_INDEX_DATA);
        buffer.putLong(tableId);
        buffer.putLong(indexId);
        
        // Encode index column values
        for (Object value : indexValues) {
            byte[] encoded = encodeValue(value);
            buffer.put(encoded);
        }
        
        // Encode primary key values (to make key unique)
        for (Object value : pkValues) {
            byte[] encoded = encodeValue(value);
            buffer.put(encoded);
        }
        
        // Encode timestamp (inverted)
        buffer.putLong(MAX_TIMESTAMP - timestamp);
        
        byte[] result = new byte[buffer.position()];
        buffer.rewind();
        buffer.get(result);
        return result;
    }
    
    /**
     * Encode a schema metadata key
     */
    public static byte[] encodeSchemaKey(long tableId, String metadataType) {
        ByteBuffer buffer = ByteBuffer.allocate(256);
        
        buffer.put(NAMESPACE_SCHEMA);
        buffer.putLong(tableId);
        buffer.putLong(0L); // Reserved
        
        byte[] typeBytes = metadataType.getBytes(StandardCharsets.UTF_8);
        buffer.put(typeBytes);
        
        byte[] result = new byte[buffer.position()];
        buffer.rewind();
        buffer.get(result);
        return result;
    }
    
    /**
     * Encode a transaction lock key
     */
    public static byte[] encodeTransactionKey(long tableId, List<Object> pkValues) {
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        
        buffer.put(NAMESPACE_TRANSACTION);
        buffer.putLong(tableId);
        buffer.putLong(0L); // Reserved
        
        for (Object value : pkValues) {
            byte[] encoded = encodeValue(value);
            buffer.put(encoded);
        }
        
        byte[] result = new byte[buffer.position()];
        buffer.rewind();
        buffer.get(result);
        return result;
    }
    
    /**
     * Encode a sequence key
     */
    public static byte[] encodeSequenceKey(long tableId, String sequenceName) {
        ByteBuffer buffer = ByteBuffer.allocate(256);
        
        buffer.put(NAMESPACE_SEQUENCE);
        buffer.putLong(tableId);
        buffer.putLong(0L); // Reserved
        
        byte[] nameBytes = sequenceName.getBytes(StandardCharsets.UTF_8);
        buffer.put(nameBytes);
        
        byte[] result = new byte[buffer.position()];
        buffer.rewind();
        buffer.get(result);
        return result;
    }
    
    /**
     * Encode a single value with sort-order preservation.
     * 
     * Format: [TypePrefix:1][EncodedValue:*]
     * 
     * Type prefix ensures:
     * 1. NULL < INT < LONG < STRING < BOOLEAN < DOUBLE < FLOAT < BYTES
     * 2. Values of different types never collide
     * 3. Schema evolution is safe (changing INT to STRING won't cause key collisions)
     */
    public static byte[] encodeValue(Object value) {
        if (value == null) {
            return new byte[]{TYPE_NULL}; // NULL sorts first
        }
        
        ByteBuffer buffer;
        
        if (value instanceof Integer) {
            byte[] encoded = encodeInt((Integer) value);
            buffer = ByteBuffer.allocate(1 + encoded.length);
            buffer.put(TYPE_INT);
            buffer.put(encoded);
            return buffer.array();
        } else if (value instanceof Long) {
            byte[] encoded = encodeLong((Long) value);
            buffer = ByteBuffer.allocate(1 + encoded.length);
            buffer.put(TYPE_LONG);
            buffer.put(encoded);
            return buffer.array();
        } else if (value instanceof java.math.BigDecimal) {
            // Handle BigDecimal from Calcite - convert to appropriate type
            java.math.BigDecimal bd = (java.math.BigDecimal) value;
            if (bd.scale() == 0) {
                // Integer value
                byte[] encoded = encodeInt(bd.intValue());
                buffer = ByteBuffer.allocate(1 + encoded.length);
                buffer.put(TYPE_INT);
                buffer.put(encoded);
                return buffer.array();
            } else {
                // Decimal value
                byte[] encoded = encodeDouble(bd.doubleValue());
                buffer = ByteBuffer.allocate(1 + encoded.length);
                buffer.put(TYPE_DOUBLE);
                buffer.put(encoded);
                return buffer.array();
            }
        } else if (value instanceof java.math.BigInteger) {
            byte[] encoded = encodeLong(((java.math.BigInteger) value).longValue());
            buffer = ByteBuffer.allocate(1 + encoded.length);
            buffer.put(TYPE_LONG);
            buffer.put(encoded);
            return buffer.array();
        } else if (value instanceof String) {
            byte[] encoded = encodeString((String) value);
            buffer = ByteBuffer.allocate(1 + encoded.length);
            buffer.put(TYPE_STRING);
            buffer.put(encoded);
            return buffer.array();
        } else if (value instanceof Boolean) {
            byte[] encoded = encodeBoolean((Boolean) value);
            buffer = ByteBuffer.allocate(1 + encoded.length);
            buffer.put(TYPE_BOOLEAN);
            buffer.put(encoded);
            return buffer.array();
        } else if (value instanceof Double) {
            byte[] encoded = encodeDouble((Double) value);
            buffer = ByteBuffer.allocate(1 + encoded.length);
            buffer.put(TYPE_DOUBLE);
            buffer.put(encoded);
            return buffer.array();
        } else if (value instanceof Float) {
            byte[] encoded = encodeFloat((Float) value);
            buffer = ByteBuffer.allocate(1 + encoded.length);
            buffer.put(TYPE_FLOAT);
            buffer.put(encoded);
            return buffer.array();
        } else if (value instanceof byte[]) {
            byte[] encoded = encodeBytes((byte[]) value);
            buffer = ByteBuffer.allocate(1 + encoded.length);
            buffer.put(TYPE_BYTES);
            buffer.put(encoded);
            return buffer.array();
        } else {
            throw new IllegalArgumentException("Unsupported value type: " + value.getClass());
        }
    }
    
    /**
     * Encode integer with sort-order preservation
     * Transform value so that byte-wise comparison preserves numeric order
     * Flip the sign bit: negative numbers become 0x00-0x7F, positive become 0x80-0xFF
     */
    private static byte[] encodeInt(int value) {
        ByteBuffer buffer = ByteBuffer.allocate(4);
        // XOR with sign bit to flip it
        buffer.putInt(value ^ 0x80000000);
        return buffer.array();
    }
    
    /**
     * Encode long with sort-order preservation
     * Transform value so that byte-wise comparison preserves numeric order
     */
    private static byte[] encodeLong(long value) {
        ByteBuffer buffer = ByteBuffer.allocate(8);
        // XOR with sign bit to flip it
        buffer.putLong(value ^ 0x8000000000000000L);
        return buffer.array();
    }
    
    /**
     * Encode string with sort-order preservation
     * UTF-8 bytes + null terminator
     * Escape 0x00 bytes: 0x00 → 0x00 0x01
     * Terminator: 0x00 0x00
     */
    private static byte[] encodeString(String value) {
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(utf8.length * 2 + 2); // Worst case: all nulls
        
        for (byte b : utf8) {
            if (b == 0x00) {
                buffer.put((byte) 0x00);
                buffer.put((byte) 0x01); // Escape
            } else {
                buffer.put(b);
            }
        }
        
        // Terminator
        buffer.put((byte) 0x00);
        buffer.put((byte) 0x00);
        
        byte[] result = new byte[buffer.position()];
        buffer.rewind();
        buffer.get(result);
        return result;
    }
    
    /**
     * Encode boolean
     */
    private static byte[] encodeBoolean(boolean value) {
        return new byte[]{value ? (byte) 0x01 : (byte) 0x00};
    }
    
    /**
     * Encode double with sort-order preservation
     * Use IEEE 754 with sign bit flipping
     */
    private static byte[] encodeDouble(double value) {
        long bits = Double.doubleToLongBits(value);
        
        // Flip sign bit for positive numbers, flip all bits for negative
        if (value >= 0) {
            bits ^= 0x8000000000000000L; // Flip sign bit
        } else {
            bits ^= 0xFFFFFFFFFFFFFFFFL; // Flip all bits
        }
        
        return ByteBuffer.allocate(8).putLong(bits).array();
    }
    
    /**
     * Encode float with sort-order preservation
     */
    private static byte[] encodeFloat(float value) {
        int bits = Float.floatToIntBits(value);
        
        if (value >= 0) {
            bits ^= 0x80000000; // Flip sign bit
        } else {
            bits ^= 0xFFFFFFFF; // Flip all bits
        }
        
        return ByteBuffer.allocate(4).putInt(bits).array();
    }
    
    /**
     * Encode binary data
     */
    private static byte[] encodeBytes(byte[] value) {
        // Length prefix + data
        ByteBuffer buffer = ByteBuffer.allocate(4 + value.length);
        buffer.putInt(value.length);
        buffer.put(value);
        return buffer.array();
    }
    
    /**
     * Decode integer
     */
    public static int decodeInt(ByteBuffer buffer) {
        int encoded = buffer.getInt();
        // XOR with sign bit to flip it back
        return encoded ^ 0x80000000;
    }
    
    /**
     * Decode long
     */
    public static long decodeLong(ByteBuffer buffer) {
        long encoded = buffer.getLong();
        // XOR with sign bit to flip it back
        return encoded ^ 0x8000000000000000L;
    }
    
    /**
     * Decode string
     */
    public static String decodeString(ByteBuffer buffer) {
        List<Byte> bytes = new ArrayList<>();
        
        while (buffer.hasRemaining()) {
            byte b1 = buffer.get();
            
            if (b1 == 0x00) {
                if (!buffer.hasRemaining()) {
                    break; // End of string
                }
                byte b2 = buffer.get();
                if (b2 == 0x00) {
                    break; // Terminator
                } else if (b2 == 0x01) {
                    bytes.add((byte) 0x00); // Escaped null
                }
            } else {
                bytes.add(b1);
            }
        }
        
        byte[] byteArray = new byte[bytes.size()];
        for (int i = 0; i < bytes.size(); i++) {
            byteArray[i] = bytes.get(i);
        }
        
        return new String(byteArray, StandardCharsets.UTF_8);
    }
    
    /**
     * Decode boolean
     */
    public static boolean decodeBoolean(ByteBuffer buffer) {
        return buffer.get() == 0x01;
    }
    
    /**
     * Decode double
     */
    public static double decodeDouble(ByteBuffer buffer) {
        long bits = buffer.getLong();
        
        // Determine if original was positive or negative
        if ((bits & 0x8000000000000000L) != 0) {
            // Was positive
            bits ^= 0x8000000000000000L;
        } else {
            // Was negative
            bits ^= 0xFFFFFFFFFFFFFFFFL;
        }
        
        return Double.longBitsToDouble(bits);
    }
    
    /**
     * Decode float
     */
    public static float decodeFloat(ByteBuffer buffer) {
        int bits = buffer.getInt();
        
        if ((bits & 0x80000000) != 0) {
            bits ^= 0x80000000;
        } else {
            bits ^= 0xFFFFFFFF;
        }
        
        return Float.intBitsToFloat(bits);
    }
    
    /**
     * Decode bytes
     */
    public static byte[] decodeBytes(ByteBuffer buffer) {
        int length = buffer.getInt();
        byte[] result = new byte[length];
        buffer.get(result);
        return result;
    }
    
    /**
     * Extract table ID from a key
     */
    public static long extractTableId(byte[] key) {
        ByteBuffer buffer = ByteBuffer.wrap(key);
        buffer.get(); // Skip namespace
        return buffer.getLong();
    }
    
    /**
     * Extract namespace from a key
     */
    public static byte extractNamespace(byte[] key) {
        return key[0];
    }
    
    /**
     * Extract index ID from a key
     */
    public static long extractIndexId(byte[] key) {
        ByteBuffer buffer = ByteBuffer.wrap(key);
        buffer.get(); // Skip namespace
        buffer.getLong(); // Skip table ID
        return buffer.getLong();
    }
    
    /**
     * Decode index values from an index key.
     * Returns only the indexed column values (not the PK values or timestamp).
     * 
     * @param key The index key
     * @param indexColumnTypes The types of the indexed columns
     * @return List of index column values
     */
    public static List<Object> decodeIndexValues(byte[] key, List<Class<?>> indexColumnTypes) {
        ByteBuffer buffer = ByteBuffer.wrap(key);
        
        // Skip header: namespace (1) + table_id (8) + index_id (8) = 17 bytes
        buffer.get(); // namespace
        buffer.getLong(); // table_id
        buffer.getLong(); // index_id
        
        // Decode index column values
        List<Object> indexValues = new ArrayList<>();
        for (Class<?> type : indexColumnTypes) {
            Object value = decodeValueByType(buffer, type);
            indexValues.add(value);
        }
        
        // Note: We don't decode PK values or timestamp - we only need index values for cardinality
        return indexValues;
    }
    
    /**
     * Decode a value from a ByteBuffer based on its type.
     */
    /**
     * Decode a value by type (with type prefix).
     */
    private static Object decodeValueByType(ByteBuffer buffer, Class<?> type) {
        // Read type prefix
        byte typePrefix = buffer.get();
        
        // Decode based on type prefix
        switch (typePrefix) {
            case TYPE_NULL:
                return null;
            case TYPE_INT:
                return decodeInt(buffer);
            case TYPE_LONG:
                return decodeLong(buffer);
            case TYPE_STRING:
                return decodeString(buffer);
            case TYPE_BOOLEAN:
                return decodeBoolean(buffer);
            case TYPE_DOUBLE:
                return decodeDouble(buffer);
            case TYPE_FLOAT:
                return decodeFloat(buffer);
            case TYPE_BYTES:
                return decodeBytes(buffer);
            default:
                throw new IllegalArgumentException("Unknown type prefix: " + typePrefix);
        }
    }
    
    /**
     * Create a prefix key for scanning (without timestamp)
     */
    public static byte[] createPrefixKey(long tableId, long indexId) {
        ByteBuffer buffer = ByteBuffer.allocate(17);
        buffer.put(indexId == PRIMARY_INDEX_ID ? NAMESPACE_TABLE_DATA : NAMESPACE_INDEX_DATA);
        buffer.putLong(tableId);
        buffer.putLong(indexId);
        return buffer.array();
    }
    
    /**
     * Create a range start key for scanning
     */
    public static byte[] createRangeStartKey(long tableId, long indexId, List<Object> startValues) {
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        buffer.put(indexId == PRIMARY_INDEX_ID ? NAMESPACE_TABLE_DATA : NAMESPACE_INDEX_DATA);
        buffer.putLong(tableId);
        buffer.putLong(indexId);
        
        if (startValues != null) {
            for (Object value : startValues) {
                byte[] encoded = encodeValue(value);
                buffer.put(encoded);
            }
        }
        
        // NOTE: Timestamp is NOT in the key anymore - it's a separate column (ts)
        // Don't append timestamp to range keys
        
        byte[] result = new byte[buffer.position()];
        buffer.rewind();
        buffer.get(result);
        return result;
    }
    
    /**
     * Create a range end key for scanning
     */
    public static byte[] createRangeEndKey(long tableId, long indexId, List<Object> endValues) {
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        buffer.put(indexId == PRIMARY_INDEX_ID ? NAMESPACE_TABLE_DATA : NAMESPACE_INDEX_DATA);
        buffer.putLong(tableId);
        buffer.putLong(indexId);
        
        if (endValues != null) {
            for (Object value : endValues) {
                byte[] encoded = encodeValue(value);
                buffer.put(encoded);
            }
        }
        
        // NOTE: Timestamp is NOT in the key anymore - it's a separate column (ts)
        // Don't append timestamp to range keys
        // Add 0xFF to make this an exclusive upper bound
        buffer.put((byte) 0xFF);
        
        byte[] result = new byte[buffer.position()];
        buffer.rewind();
        buffer.get(result);
        return result;
    }
    
    /**
     * Decode primary key values from a table data key
     * 
     * @param key The encoded key
     * @param pkColumnTypes The types of the primary key columns
     * @return List of decoded primary key values
     */
    public static List<Object> decodeTableDataKey(byte[] key, List<Class<?>> pkColumnTypes) {
        ByteBuffer buffer = ByteBuffer.wrap(key);
        
        // Skip namespace (1 byte)
        buffer.get();
        
        // Skip table ID (8 bytes)
        buffer.getLong();
        
        // Skip index ID (8 bytes)
        buffer.getLong();
        
        // Decode primary key values
        List<Object> pkValues = new ArrayList<>();
        for (Class<?> type : pkColumnTypes) {
            Object value = decodeValue(buffer, type);
            pkValues.add(value);
        }
        
        return pkValues;
    }
    
    /**
     * Decode a single value from a ByteBuffer based on type
     */
    /**
     * Decode a value with type prefix.
     * 
     * Format: [TypePrefix:1][EncodedValue:*]
     */
    private static Object decodeValue(ByteBuffer buffer, Class<?> type) {
        // Read type prefix
        byte typePrefix = buffer.get();
        
        // Decode based on type prefix (ignore the expected type parameter for now)
        switch (typePrefix) {
            case TYPE_NULL:
                return null;
            case TYPE_INT:
                return decodeInt(buffer);
            case TYPE_LONG:
                return decodeLong(buffer);
            case TYPE_STRING:
                return decodeString(buffer);
            case TYPE_BOOLEAN:
                return decodeBoolean(buffer);
            case TYPE_DOUBLE:
                return decodeDouble(buffer);
            case TYPE_FLOAT:
                return decodeFloat(buffer);
            case TYPE_BYTES:
                return decodeBytes(buffer);
            default:
                throw new IllegalArgumentException("Unknown type prefix: " + typePrefix);
        }
    }
    
    /**
     * Encode table prefix for scanning all rows in a table
     */
    public byte[] encodeTablePrefix(String tableName) {
        // Get table ID from schema manager (for now, use hash of table name)
        long tableId = Math.abs(tableName.hashCode());
        
        ByteBuffer buffer = ByteBuffer.allocate(17);
        buffer.put(NAMESPACE_TABLE_DATA);
        buffer.putLong(tableId);
        buffer.putLong(PRIMARY_INDEX_ID);
        
        byte[] result = new byte[buffer.position()];
        buffer.rewind();
        buffer.get(result);
        return result;
    }
    
    /**
     * Encode table prefix end for scanning (exclusive end)
     */
    public byte[] encodeTablePrefixEnd(String tableName) {
        byte[] prefix = encodeTablePrefix(tableName);
        // Increment last byte to get exclusive end
        byte[] end = new byte[prefix.length];
        System.arraycopy(prefix, 0, end, 0, prefix.length);
        
        // Increment the last byte (or carry over if 0xFF)
        for (int i = end.length - 1; i >= 0; i--) {
            if (end[i] != (byte) 0xFF) {
                end[i]++;
                break;
            } else {
                end[i] = 0;
            }
        }
        
        return end;
    }
    
    // ========== View-related keys ==========
    
    /**
     * Encode view metadata key
     * Format: [NAMESPACE_SCHEMA][ViewID:8]
     */
    public static byte[] encodeViewMetadataKey(long viewId) {
        ByteBuffer buffer = ByteBuffer.allocate(9);
        buffer.put(NAMESPACE_SCHEMA);
        buffer.putLong(viewId);
        return buffer.array();
    }
    
    /**
     * Create range start key for scanning all view metadata
     */
    public static byte[] createViewMetadataRangeStart() {
        ByteBuffer buffer = ByteBuffer.allocate(9);
        buffer.put(NAMESPACE_SCHEMA);
        buffer.putLong(Long.MIN_VALUE + 1000); // Start after special IDs
        return buffer.array();
    }
    
    /**
     * Create range end key for scanning all view metadata
     */
    public static byte[] createViewMetadataRangeEnd() {
        ByteBuffer buffer = ByteBuffer.allocate(9);
        buffer.put(NAMESPACE_SCHEMA);
        buffer.putLong(Long.MIN_VALUE + 10000); // End before table metadata
        return buffer.array();
    }
    
    /**
     * Encode materialized view data key
     * Format: [NAMESPACE_TABLE_DATA][ViewID:8][PRIMARY_INDEX_ID:8][EncodedKey:*][Timestamp:8]
     * 
     * Materialized views are stored like tables, using the view ID as the table ID
     */
    public static byte[] encodeMaterializedViewDataKey(long viewId, List<Object> pkValues, long timestamp) {
        return encodeTableDataKey(viewId, pkValues, timestamp);
    }
    
    /**
     * Decode a materialized view data key to extract primary key values
     * Format: [NAMESPACE_DATA:1][ViewID:8][RowID:8][Timestamp:8]
     */
    public static List<Object> decodeMaterializedViewDataKey(byte[] key) {
        // For materialized views, the PK is just the row ID (a long)
        // Skip namespace (1 byte) + viewId (8 bytes) = 9 bytes
        // Then read rowId (8 bytes)
        if (key.length < 17) {
            throw new IllegalArgumentException("Key too short to decode materialized view data key");
        }
        
        ByteBuffer buffer = ByteBuffer.wrap(key);
        buffer.get(); // Skip namespace
        buffer.getLong(); // Skip viewId
        long rowId = buffer.getLong(); // Read rowId
        
        List<Object> pkValues = new ArrayList<>();
        pkValues.add(rowId);
        return pkValues;
    }
    
    /**
     * Encode ENUM metadata key
     * Format: [NAMESPACE_ENUM][TypeName]
     */
    public static byte[] encodeEnumMetadataKey(String typeName) {
        byte[] typeNameBytes = typeName.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(1 + typeNameBytes.length);
        buffer.put(NAMESPACE_ENUM);
        buffer.put(typeNameBytes);
        return buffer.array();
    }
    
    /**
     * Decode a key to extract table ID and primary key values.
     * This is used for buffered write overlay in transactions.
     */
    public static DecodedKey decodeKey(byte[] key) {
        ByteBuffer buffer = ByteBuffer.wrap(key);
        
        byte namespace = buffer.get();
        long tableId = buffer.getLong();
        long indexId = buffer.getLong();
        
        // Decode primary key values
        List<Object> pkValues = new ArrayList<>();
        while (buffer.hasRemaining()) {
            Object value = decodeValue(buffer);
            if (value != null) {
                pkValues.add(value);
            }
        }
        
        return new DecodedKey(namespace, tableId, indexId, pkValues);
    }
    
    /**
     * Decode a single value from a ByteBuffer
     */
    private static Object decodeValue(ByteBuffer buffer) {
        if (!buffer.hasRemaining()) {
            return null;
        }
        
        byte type = buffer.get();
        
        switch (type) {
            case TYPE_NULL:
                return null;
            case TYPE_INT:
                return buffer.getInt();
            case TYPE_LONG:
                return buffer.getLong();
            case TYPE_DOUBLE:
                return buffer.getDouble();
            case TYPE_FLOAT:
                return buffer.getFloat();
            case TYPE_BOOLEAN:
                return buffer.get() != 0;
            case TYPE_STRING:
                int strLen = buffer.getInt();
                byte[] strBytes = new byte[strLen];
                buffer.get(strBytes);
                return new String(strBytes, StandardCharsets.UTF_8);
            case TYPE_BYTES:
                int bytesLen = buffer.getInt();
                byte[] bytes = new byte[bytesLen];
                buffer.get(bytes);
                return bytes;
            default:
                throw new IllegalArgumentException("Unknown type prefix: " + type);
        }
    }
    
    /**
     * Decoded key information
     */
    public static class DecodedKey {
        private final byte namespace;
        private final long tableId;
        private final long indexId;
        private final List<Object> primaryKeyValues;
        
        public DecodedKey(byte namespace, long tableId, long indexId, List<Object> primaryKeyValues) {
            this.namespace = namespace;
            this.tableId = tableId;
            this.indexId = indexId;
            this.primaryKeyValues = primaryKeyValues;
        }
        
        public byte getNamespace() {
            return namespace;
        }
        
        public long getTableId() {
            return tableId;
        }
        
        public long getIndexId() {
            return indexId;
        }
        
        public List<Object> getPrimaryKeyValues() {
            return primaryKeyValues;
        }
    }
}

