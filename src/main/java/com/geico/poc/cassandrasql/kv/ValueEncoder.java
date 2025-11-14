package com.geico.poc.cassandrasql.kv;

import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Encodes and decodes values for key-value storage mode.
 * 
 * Value format: [Version:1][Flags:1][ValueData:*]
 * 
 * For table data, ValueData is: [ColumnCount:2][Column1Length:4][Column1Data:*]...
 */
@Component
public class ValueEncoder {
    
    // Current encoding version
    public static final byte VERSION_1 = 0x01;
    
    // Flag bits
    public static final byte FLAG_COMPRESSED = 0x01;  // Bit 0
    public static final byte FLAG_ENCRYPTED = 0x02;   // Bit 1
    public static final byte FLAG_PARTIAL = 0x04;     // Bit 2
    
    // Special length values
    public static final int NULL_LENGTH = 0xFFFFFFFF;
    public static final int EMPTY_LENGTH = 0x00000000;
    
    // Type prefixes for self-describing values (same as KeyEncoder)
    private static final byte TYPE_NULL = 0x00;
    private static final byte TYPE_INT = 0x01;
    private static final byte TYPE_LONG = 0x02;
    private static final byte TYPE_STRING = 0x03;
    private static final byte TYPE_BOOLEAN = 0x04;
    private static final byte TYPE_DOUBLE = 0x05;
    private static final byte TYPE_FLOAT = 0x06;
    private static final byte TYPE_BYTES = 0x07;
    private static final byte TYPE_DATE = 0x08;
    private static final byte TYPE_TIME = 0x09;
    private static final byte TYPE_TIMESTAMP = 0x0A;
    private static final byte TYPE_ARRAY = 0x0B;
    
    /**
     * Encode a row (all non-PK columns)
     */
    public static byte[] encodeRow(List<Object> columnValues) {
        return encodeRow(columnValues, false, false);
    }
    
    /**
     * Encode a row with optional compression and encryption
     */
    public static byte[] encodeRow(List<Object> columnValues, boolean compress, boolean encrypt) {
        // Calculate required size
        int requiredSize = 2 + 2; // Version + Flags + Column count
        for (Object value : columnValues) {
            if (value == null) {
                requiredSize += 4; // NULL_LENGTH marker
            } else {
                byte[] encoded = encodeColumnValue(value);
                requiredSize += 4 + encoded.length; // Length + data
            }
        }
        
        ByteBuffer buffer = ByteBuffer.allocate(requiredSize);
        
        // Version
        buffer.put(VERSION_1);
        
        // Flags
        byte flags = 0;
        if (compress) flags |= FLAG_COMPRESSED;
        if (encrypt) flags |= FLAG_ENCRYPTED;
        buffer.put(flags);
        
        // Column count
        buffer.putShort((short) columnValues.size());
        
        // Encode each column
        for (Object value : columnValues) {
            if (value == null) {
                buffer.putInt(NULL_LENGTH);
            } else {
                byte[] encoded = encodeColumnValue(value);
                buffer.putInt(encoded.length);
                buffer.put(encoded);
            }
        }
        
        // TODO: Apply compression if flag is set
        // TODO: Apply encryption if flag is set
        
        // Return only the used portion
        byte[] result = new byte[buffer.position()];
        buffer.rewind();
        buffer.get(result);
        return result;
    }
    
    /**
     * Decode a row
     */
    public static List<Object> decodeRow(byte[] data, List<Class<?>> columnTypes) {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        
        // Version
        byte version = buffer.get();
        if (version != VERSION_1) {
            throw new IllegalArgumentException("Unsupported encoding version: " + version);
        }
        
        // Flags
        byte flags = buffer.get();
        boolean compressed = (flags & FLAG_COMPRESSED) != 0;
        boolean encrypted = (flags & FLAG_ENCRYPTED) != 0;
        
        // TODO: Apply decryption if flag is set
        // TODO: Apply decompression if flag is set
        
        // Column count
        short columnCount = buffer.getShort();
        
        List<Object> values = new ArrayList<>(columnCount);
        
        // Decode each column
        for (int i = 0; i < columnCount; i++) {
            int length = buffer.getInt();
            
            if (length == NULL_LENGTH) {
                values.add(null);
            } else if (length == EMPTY_LENGTH) {
                // Empty value - determine based on type
                Class<?> type = (i < columnTypes.size()) ? columnTypes.get(i) : String.class;
                values.add(getEmptyValue(type));
            } else {
                byte[] columnData = new byte[length];
                buffer.get(columnData);
                
                Class<?> type = (i < columnTypes.size()) ? columnTypes.get(i) : String.class;
                Object value = decodeColumnValue(columnData, type);
                values.add(value);
            }
        }
        
        return values;
    }
    
    /**
     * Encode a single column value with type prefix for self-describing format.
     * 
     * Format: [TypePrefix:1][Value:*]
     * 
     * This allows:
     * 1. Schema evolution (changing column types)
     * 2. Decoding without external type information
     * 3. Type safety and validation
     */
    private static byte[] encodeColumnValue(Object value) {
        ByteBuffer buffer;
        
        if (value instanceof Integer) {
            buffer = ByteBuffer.allocate(1 + 4);
            buffer.put(TYPE_INT);
            buffer.putInt((Integer) value);
            return buffer.array();
        } else if (value instanceof Long) {
            buffer = ByteBuffer.allocate(1 + 8);
            buffer.put(TYPE_LONG);
            buffer.putLong((Long) value);
            return buffer.array();
        } else if (value instanceof java.math.BigDecimal) {
            // Handle BigDecimal from Calcite
            java.math.BigDecimal bd = (java.math.BigDecimal) value;
            if (bd.scale() == 0) {
                // Integer value
                buffer = ByteBuffer.allocate(1 + 4);
                buffer.put(TYPE_INT);
                buffer.putInt(bd.intValue());
                return buffer.array();
            } else {
                // Decimal value
                buffer = ByteBuffer.allocate(1 + 8);
                buffer.put(TYPE_DOUBLE);
                buffer.putDouble(bd.doubleValue());
                return buffer.array();
            }
        } else if (value instanceof java.math.BigInteger) {
            buffer = ByteBuffer.allocate(1 + 8);
            buffer.put(TYPE_LONG);
            buffer.putLong(((java.math.BigInteger) value).longValue());
            return buffer.array();
        } else if (value instanceof String) {
            byte[] strBytes = ((String) value).getBytes(StandardCharsets.UTF_8);
            buffer = ByteBuffer.allocate(1 + strBytes.length);
            buffer.put(TYPE_STRING);
            buffer.put(strBytes);
            return buffer.array();
        } else if (value instanceof Boolean) {
            buffer = ByteBuffer.allocate(1 + 1);
            buffer.put(TYPE_BOOLEAN);
            buffer.put((byte) ((Boolean) value ? 1 : 0));
            return buffer.array();
        } else if (value instanceof Double) {
            buffer = ByteBuffer.allocate(1 + 8);
            buffer.put(TYPE_DOUBLE);
            buffer.putDouble((Double) value);
            return buffer.array();
        } else if (value instanceof Float) {
            buffer = ByteBuffer.allocate(1 + 4);
            buffer.put(TYPE_FLOAT);
            buffer.putFloat((Float) value);
            return buffer.array();
        } else if (value instanceof byte[]) {
            byte[] bytes = (byte[]) value;
            buffer = ByteBuffer.allocate(1 + bytes.length);
            buffer.put(TYPE_BYTES);
            buffer.put(bytes);
            return buffer.array();
        } else if (value instanceof java.sql.Date) {
            // Store DATE as long (milliseconds since epoch)
            buffer = ByteBuffer.allocate(1 + 8);
            buffer.put(TYPE_DATE);
            buffer.putLong(((java.sql.Date) value).getTime());
            return buffer.array();
        } else if (value instanceof java.sql.Time) {
            // Store TIME as long (milliseconds since midnight)
            buffer = ByteBuffer.allocate(1 + 8);
            buffer.put(TYPE_TIME);
            buffer.putLong(((java.sql.Time) value).getTime());
            return buffer.array();
        } else if (value instanceof java.sql.Timestamp) {
            // Store TIMESTAMP as long (milliseconds since epoch)
            buffer = ByteBuffer.allocate(1 + 8);
            buffer.put(TYPE_TIMESTAMP);
            buffer.putLong(((java.sql.Timestamp) value).getTime());
            return buffer.array();
        } else if (value instanceof java.util.Date) {
            // Generic java.util.Date - treat as TIMESTAMP
            buffer = ByteBuffer.allocate(1 + 8);
            buffer.put(TYPE_TIMESTAMP);
            buffer.putLong(((java.util.Date) value).getTime());
            return buffer.array();
        } else if (value instanceof List) {
            // Handle List (from ARRAY type)
            return encodeArray((List<?>) value);
        } else if (value.getClass().isArray()) {
            // Handle native Java arrays (Object[], String[], etc.)
            return encodeArray(arrayToList(value));
        } else {
            // Fallback: toString as STRING
            byte[] strBytes = value.toString().getBytes(StandardCharsets.UTF_8);
            buffer = ByteBuffer.allocate(1 + strBytes.length);
            buffer.put(TYPE_STRING);
            buffer.put(strBytes);
            return buffer.array();
        }
    }
    
    /**
     * Decode a single column value using type prefix (self-describing).
     * 
     * Format: [TypePrefix:1][Value:*]
     * 
     * The type parameter is ignored - we decode based on the type prefix.
     */
    private static Object decodeColumnValue(byte[] data, Class<?> type) {
        if (data.length == 0) {
            return null;
        }
        
        ByteBuffer buffer = ByteBuffer.wrap(data);
        byte typePrefix = buffer.get();
        
        switch (typePrefix) {
            case TYPE_NULL:
                return null;
            case TYPE_INT:
                return buffer.getInt();
            case TYPE_LONG:
                return buffer.getLong();
            case TYPE_STRING:
                byte[] strBytes = new byte[buffer.remaining()];
                buffer.get(strBytes);
                return new String(strBytes, StandardCharsets.UTF_8);
            case TYPE_BOOLEAN:
                return buffer.get() != 0;
            case TYPE_DOUBLE:
                return buffer.getDouble();
            case TYPE_FLOAT:
                return buffer.getFloat();
            case TYPE_BYTES:
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                return bytes;
            case TYPE_DATE:
                return new java.sql.Date(buffer.getLong());
            case TYPE_TIME:
                return new java.sql.Time(buffer.getLong());
            case TYPE_TIMESTAMP:
                return new java.sql.Timestamp(buffer.getLong());
            case TYPE_ARRAY:
                return decodeArray(buffer);
            default:
                throw new IllegalArgumentException("Unknown type prefix: " + typePrefix);
        }
    }
    
    /**
     * Encode an array/list as: [TYPE_ARRAY][elementCount:4][element1Length:4][element1Data:*]...
     * Each element is encoded with its length prefix to support variable-length types
     */
    private static byte[] encodeArray(List<?> list) {
        if (list == null || list.isEmpty()) {
            // Empty array: just type prefix and zero count
            ByteBuffer buffer = ByteBuffer.allocate(1 + 4);
            buffer.put(TYPE_ARRAY);
            buffer.putInt(0);
            return buffer.array();
        }
        
        // First pass: encode all elements to determine total size
        List<byte[]> encodedElements = new ArrayList<>(list.size());
        int totalSize = 1 + 4; // type prefix + element count
        
        for (Object element : list) {
            byte[] encoded = encodeColumnValue(element);
            encodedElements.add(encoded);
            totalSize += 4 + encoded.length; // 4 bytes for length + data
        }
        
        // Second pass: write to buffer
        ByteBuffer buffer = ByteBuffer.allocate(totalSize);
        buffer.put(TYPE_ARRAY);
        buffer.putInt(list.size());
        
        for (byte[] encoded : encodedElements) {
            buffer.putInt(encoded.length);
            buffer.put(encoded);
        }
        
        return buffer.array();
    }
    
    /**
     * Decode an array from buffer (already positioned after TYPE_ARRAY prefix)
     * Format: [elementCount:4][element1Length:4][element1Data:*]...
     */
    private static List<Object> decodeArray(ByteBuffer buffer) {
        int elementCount = buffer.getInt();
        
        if (elementCount == 0) {
            return new ArrayList<>();
        }
        
        List<Object> result = new ArrayList<>(elementCount);
        
        for (int i = 0; i < elementCount; i++) {
            // Read element length
            int elementLength = buffer.getInt();
            
            // Read element data
            byte[] elementData = new byte[elementLength];
            buffer.get(elementData);
            
            // Decode element using the standard decodeColumnValue
            Object element = decodeColumnValue(elementData, Object.class);
            result.add(element);
        }
        
        return result;
    }
    
    /**
     * Convert a native Java array to a List
     */
    private static List<Object> arrayToList(Object array) {
        if (!array.getClass().isArray()) {
            throw new IllegalArgumentException("Not an array: " + array.getClass());
        }
        
        List<Object> list = new ArrayList<>();
        
        // Handle primitive arrays
        if (array instanceof int[]) {
            for (int val : (int[]) array) {
                list.add(val);
            }
        } else if (array instanceof long[]) {
            for (long val : (long[]) array) {
                list.add(val);
            }
        } else if (array instanceof double[]) {
            for (double val : (double[]) array) {
                list.add(val);
            }
        } else if (array instanceof float[]) {
            for (float val : (float[]) array) {
                list.add(val);
            }
        } else if (array instanceof boolean[]) {
            for (boolean val : (boolean[]) array) {
                list.add(val);
            }
        } else if (array instanceof byte[]) {
            for (byte val : (byte[]) array) {
                list.add(val);
            }
        } else if (array instanceof short[]) {
            for (short val : (short[]) array) {
                list.add(val);
            }
        } else if (array instanceof char[]) {
            for (char val : (char[]) array) {
                list.add(val);
            }
        } else {
            // Object array
            Object[] objArray = (Object[]) array;
            for (Object val : objArray) {
                list.add(val);
            }
        }
        
        return list;
    }
    
    /**
     * Get empty value for a type
     */
    private static Object getEmptyValue(Class<?> type) {
        if (type == Integer.class || type == int.class) {
            return 0;
        } else if (type == Long.class || type == long.class) {
            return 0L;
        } else if (type == String.class) {
            return "";
        } else if (type == Boolean.class || type == boolean.class) {
            return false;
        } else if (type == Double.class || type == double.class) {
            return 0.0;
        } else if (type == Float.class || type == float.class) {
            return 0.0f;
        } else if (type == byte[].class) {
            return new byte[0];
        } else if (type == java.sql.Date.class) {
            return new java.sql.Date(0);
        } else if (type == java.sql.Time.class) {
            return new java.sql.Time(0);
        } else if (type == java.sql.Timestamp.class) {
            return new java.sql.Timestamp(0);
        } else {
            return null;
        }
    }
    
    /**
     * Encode schema metadata (JSON format)
     */
    public static byte[] encodeSchema(Map<String, Object> schema) {
        // For now, use simple JSON-like string representation
        // TODO: Use proper JSON library or protobuf
        StringBuilder sb = new StringBuilder("{");
        
        boolean first = true;
        for (Map.Entry<String, Object> entry : schema.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            
            sb.append("\"").append(entry.getKey()).append("\":");
            sb.append(toJsonValue(entry.getValue()));
        }
        
        sb.append("}");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }
    
    /**
     * Decode schema metadata
     */
    public static Map<String, Object> decodeSchema(byte[] data) {
        // TODO: Implement proper JSON parsing
        // For now, return empty map
        return new HashMap<>();
    }
    
    /**
     * Convert value to JSON representation
     */
    private static String toJsonValue(Object value) {
        if (value == null) {
            return "null";
        } else if (value instanceof String) {
            return "\"" + value + "\"";
        } else if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        } else if (value instanceof List) {
            StringBuilder sb = new StringBuilder("[");
            List<?> list = (List<?>) value;
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(toJsonValue(list.get(i)));
            }
            sb.append("]");
            return sb.toString();
        } else if (value instanceof Map) {
            StringBuilder sb = new StringBuilder("{");
            Map<?, ?> map = (Map<?, ?>) value;
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) sb.append(",");
                first = false;
                sb.append("\"").append(entry.getKey()).append("\":");
                sb.append(toJsonValue(entry.getValue()));
            }
            sb.append("}");
            return sb.toString();
        } else {
            return "\"" + value.toString() + "\"";
        }
    }
    
    /**
     * Encode an index entry (typically empty, as key contains all data)
     */
    public static byte[] encodeIndexEntry() {
        ByteBuffer buffer = ByteBuffer.allocate(2);
        buffer.put(VERSION_1);
        buffer.put((byte) 0); // No flags
        return buffer.array();
    }
    
    /**
     * Check if value is compressed
     */
    public static boolean isCompressed(byte[] data) {
        if (data.length < 2) return false;
        return (data[1] & FLAG_COMPRESSED) != 0;
    }
    
    /**
     * Check if value is encrypted
     */
    public static boolean isEncrypted(byte[] data) {
        if (data.length < 2) return false;
        return (data[1] & FLAG_ENCRYPTED) != 0;
    }
    
    /**
     * Check if value is partial update
     */
    public static boolean isPartial(byte[] data) {
        if (data.length < 2) return false;
        return (data[1] & FLAG_PARTIAL) != 0;
    }
    
    /**
     * Get encoding version
     */
    public static byte getVersion(byte[] data) {
        if (data.length < 1) return 0;
        return data[0];
    }
}

