package com.geico.poc.cassandrasql.kv;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for KeyEncoder to verify:
 * 1. Sort-order preservation
 * 2. Type prefix correctness
 * 3. Encode/decode round-trip
 * 4. Key adjacency for table scans
 */
public class KeyEncoderTest {
    
    // ==================== Type Prefix Tests ====================
    
    @Test
    public void testTypePrefixSortOrder() {
        // Verify that type prefixes sort in the correct order:
        // NULL < INT < LONG < STRING < BOOLEAN < DOUBLE < FLOAT < BYTES
        
        byte[] nullVal = KeyEncoder.encodeValue(null);
        byte[] intVal = KeyEncoder.encodeValue(0);
        byte[] longVal = KeyEncoder.encodeValue(0L);
        byte[] stringVal = KeyEncoder.encodeValue("");
        byte[] boolVal = KeyEncoder.encodeValue(false);
        byte[] doubleVal = KeyEncoder.encodeValue(0.0);
        byte[] floatVal = KeyEncoder.encodeValue(0.0f);
        byte[] bytesVal = KeyEncoder.encodeValue(new byte[0]);
        
        // Compare type prefixes (first byte)
        assertTrue(compare(nullVal, intVal) < 0, "NULL should sort before INT");
        assertTrue(compare(intVal, longVal) < 0, "INT should sort before LONG");
        assertTrue(compare(longVal, stringVal) < 0, "LONG should sort before STRING");
        assertTrue(compare(stringVal, boolVal) < 0, "STRING should sort before BOOLEAN");
        assertTrue(compare(boolVal, doubleVal) < 0, "BOOLEAN should sort before DOUBLE");
        assertTrue(compare(doubleVal, floatVal) < 0, "DOUBLE should sort before FLOAT");
        assertTrue(compare(floatVal, bytesVal) < 0, "FLOAT should sort before BYTES");
    }
    
    @Test
    public void testTypeCollisionPrevention() {
        // Verify that INT(5) and STRING("5") don't collide
        byte[] intFive = KeyEncoder.encodeValue(5);
        byte[] stringFive = KeyEncoder.encodeValue("5");
        
        assertNotEquals(Arrays.toString(intFive), Arrays.toString(stringFive),
            "INT(5) and STRING('5') should have different encodings");
        
        // Type prefix should be different
        assertNotEquals(intFive[0], stringFive[0], 
            "Type prefixes should differ");
    }
    
    // ==================== Integer Encoding Tests ====================
    
    @Test
    public void testIntegerSortOrder() {
        List<Integer> values = Arrays.asList(-1000, -100, -1, 0, 1, 100, 1000);
        
        for (int i = 0; i < values.size() - 1; i++) {
            byte[] encoded1 = KeyEncoder.encodeValue(values.get(i));
            byte[] encoded2 = KeyEncoder.encodeValue(values.get(i + 1));
            
            assertTrue(compare(encoded1, encoded2) < 0,
                String.format("%d should sort before %d", values.get(i), values.get(i + 1)));
        }
    }
    
    @Test
    public void testIntegerRoundTrip() {
        List<Integer> testValues = Arrays.asList(
            Integer.MIN_VALUE, -1000, -1, 0, 1, 1000, Integer.MAX_VALUE
        );
        
        for (Integer value : testValues) {
            byte[] encoded = KeyEncoder.encodeValue(value);
            ByteBuffer buffer = ByteBuffer.wrap(encoded);
            
            // Skip type prefix
            buffer.get();
            
            int decoded = KeyEncoder.decodeInt(buffer);
            assertEquals(value, decoded, "Round-trip failed for " + value);
        }
    }
    
    // ==================== Long Encoding Tests ====================
    
    @Test
    public void testLongSortOrder() {
        List<Long> values = Arrays.asList(-1000L, -100L, -1L, 0L, 1L, 100L, 1000L);
        
        for (int i = 0; i < values.size() - 1; i++) {
            byte[] encoded1 = KeyEncoder.encodeValue(values.get(i));
            byte[] encoded2 = KeyEncoder.encodeValue(values.get(i + 1));
            
            assertTrue(compare(encoded1, encoded2) < 0,
                String.format("%d should sort before %d", values.get(i), values.get(i + 1)));
        }
    }
    
    @Test
    public void testLongRoundTrip() {
        List<Long> testValues = Arrays.asList(
            Long.MIN_VALUE, -1000L, -1L, 0L, 1L, 1000L, Long.MAX_VALUE
        );
        
        for (Long value : testValues) {
            byte[] encoded = KeyEncoder.encodeValue(value);
            ByteBuffer buffer = ByteBuffer.wrap(encoded);
            
            // Skip type prefix
            buffer.get();
            
            long decoded = KeyEncoder.decodeLong(buffer);
            assertEquals(value, decoded, "Round-trip failed for " + value);
        }
    }
    
    // ==================== String Encoding Tests ====================
    
    @Test
    public void testStringSortOrder() {
        List<String> values = Arrays.asList("", "a", "alice", "bob", "carol", "z");
        
        for (int i = 0; i < values.size() - 1; i++) {
            byte[] encoded1 = KeyEncoder.encodeValue(values.get(i));
            byte[] encoded2 = KeyEncoder.encodeValue(values.get(i + 1));
            
            assertTrue(compare(encoded1, encoded2) < 0,
                String.format("'%s' should sort before '%s'", values.get(i), values.get(i + 1)));
        }
    }
    
    @Test
    public void testStringRoundTrip() {
        List<String> testValues = Arrays.asList(
            "", "hello", "world", "Hello World!", "UTF-8: 你好", "Special: \n\t\r"
        );
        
        for (String value : testValues) {
            byte[] encoded = KeyEncoder.encodeValue(value);
            ByteBuffer buffer = ByteBuffer.wrap(encoded);
            
            // Skip type prefix
            buffer.get();
            
            String decoded = KeyEncoder.decodeString(buffer);
            assertEquals(value, decoded, "Round-trip failed for '" + value + "'");
        }
    }
    
    @Test
    public void testStringWithNullBytes() {
        // Test that null bytes are properly escaped
        String value = "hello\u0000world";
        byte[] encoded = KeyEncoder.encodeValue(value);
        ByteBuffer buffer = ByteBuffer.wrap(encoded);
        
        // Skip type prefix
        buffer.get();
        
        String decoded = KeyEncoder.decodeString(buffer);
        assertEquals(value, decoded, "String with null byte failed round-trip");
    }
    
    // ==================== Double Encoding Tests ====================
    
    @Test
    public void testDoubleSortOrder() {
        List<Double> values = Arrays.asList(
            -1000.5, -100.5, -1.5, -0.5, 0.0, 0.5, 1.5, 100.5, 1000.5
        );
        
        for (int i = 0; i < values.size() - 1; i++) {
            byte[] encoded1 = KeyEncoder.encodeValue(values.get(i));
            byte[] encoded2 = KeyEncoder.encodeValue(values.get(i + 1));
            
            assertTrue(compare(encoded1, encoded2) < 0,
                String.format("%f should sort before %f", values.get(i), values.get(i + 1)));
        }
    }
    
    @Test
    public void testDoubleRoundTrip() {
        List<Double> testValues = Arrays.asList(
            Double.NEGATIVE_INFINITY, -1000.5, -1.5, 0.0, 1.5, 1000.5, Double.POSITIVE_INFINITY
        );
        
        for (Double value : testValues) {
            byte[] encoded = KeyEncoder.encodeValue(value);
            ByteBuffer buffer = ByteBuffer.wrap(encoded);
            
            // Skip type prefix
            buffer.get();
            
            double decoded = KeyEncoder.decodeDouble(buffer);
            assertEquals(value, decoded, 0.0001, "Round-trip failed for " + value);
        }
    }
    
    // ==================== Boolean Encoding Tests ====================
    
    @Test
    public void testBooleanSortOrder() {
        byte[] falseVal = KeyEncoder.encodeValue(false);
        byte[] trueVal = KeyEncoder.encodeValue(true);
        
        assertTrue(compare(falseVal, trueVal) < 0, "false should sort before true");
    }
    
    @Test
    public void testBooleanRoundTrip() {
        for (Boolean value : Arrays.asList(false, true)) {
            byte[] encoded = KeyEncoder.encodeValue(value);
            ByteBuffer buffer = ByteBuffer.wrap(encoded);
            
            // Skip type prefix
            buffer.get();
            
            boolean decoded = KeyEncoder.decodeBoolean(buffer);
            assertEquals(value, decoded, "Round-trip failed for " + value);
        }
    }
    
    // ==================== Table Key Tests ====================
    
    @Test
    public void testTableDataKeyStructure() {
        long tableId = 5;
        List<Object> pkValues = Arrays.asList(100);
        
        byte[] key = KeyEncoder.encodeTableDataKey(tableId, pkValues, 0);
        
        ByteBuffer buffer = ByteBuffer.wrap(key);
        
        // Verify structure: [Namespace:1][TableID:8][IndexID:8][TypePrefix:1][EncodedPK:*]
        assertEquals(KeyEncoder.NAMESPACE_TABLE_DATA, buffer.get(), "Namespace should be TABLE_DATA");
        assertEquals(tableId, buffer.getLong(), "TableID should match");
        assertEquals(KeyEncoder.PRIMARY_INDEX_ID, buffer.getLong(), "IndexID should be PRIMARY");
        
        // Type prefix and value
        byte typePrefix = buffer.get();
        assertEquals(0x01, typePrefix, "Type prefix should be INT");
    }
    
    @Test
    public void testTableKeyAdjacency() {
        // Verify that all rows of table 1 sort before all rows of table 2
        long table1 = 1;
        long table2 = 2;
        
        byte[] table1Row1 = KeyEncoder.encodeTableDataKey(table1, Arrays.asList(1), 0);
        byte[] table1Row999 = KeyEncoder.encodeTableDataKey(table1, Arrays.asList(999), 0);
        byte[] table2Row1 = KeyEncoder.encodeTableDataKey(table2, Arrays.asList(1), 0);
        
        assertTrue(compare(table1Row1, table1Row999) < 0, 
            "Table 1 row 1 should sort before table 1 row 999");
        assertTrue(compare(table1Row999, table2Row1) < 0,
            "Table 1 row 999 should sort before table 2 row 1");
    }
    
    @Test
    public void testPrimaryKeyBeforeSecondaryIndex() {
        // Verify that primary key data (indexId=0) sorts before secondary indexes
        long tableId = 1;
        long primaryIndexId = 0;
        long secondaryIndexId = 1;
        
        byte[] primaryKey = KeyEncoder.encodeIndexKey(tableId, primaryIndexId, 
            Arrays.asList(100), Arrays.asList(100), 0);
        byte[] secondaryKey = KeyEncoder.encodeIndexKey(tableId, secondaryIndexId,
            Arrays.asList(100), Arrays.asList(100), 0);
        
        assertTrue(compare(primaryKey, secondaryKey) < 0,
            "Primary index should sort before secondary index");
    }
    
    @Test
    public void testMultiColumnPrimaryKey() {
        // Test composite primary key: (INT, STRING)
        List<Object> pk1 = Arrays.asList(1, "alice");
        List<Object> pk2 = Arrays.asList(1, "bob");
        List<Object> pk3 = Arrays.asList(2, "alice");
        
        byte[] key1 = KeyEncoder.encodeTableDataKey(1, pk1, 0);
        byte[] key2 = KeyEncoder.encodeTableDataKey(1, pk2, 0);
        byte[] key3 = KeyEncoder.encodeTableDataKey(1, pk3, 0);
        
        assertTrue(compare(key1, key2) < 0, "(1,'alice') should sort before (1,'bob')");
        assertTrue(compare(key2, key3) < 0, "(1,'bob') should sort before (2,'alice')");
    }
    
    // ==================== Index Key Tests ====================
    
    @Test
    public void testIndexKeyStructure() {
        long tableId = 5;
        long indexId = 1;
        List<Object> indexValues = Arrays.asList(100);
        List<Object> pkValues = Arrays.asList(1);
        long timestamp = 1000;
        
        byte[] key = KeyEncoder.encodeIndexKey(tableId, indexId, indexValues, pkValues, timestamp);
        
        ByteBuffer buffer = ByteBuffer.wrap(key);
        
        // Verify structure: [Namespace:1][TableID:8][IndexID:8][TypePrefix:1][IndexValues:*][PKValues:*][InvertedTS:8]
        assertEquals(KeyEncoder.NAMESPACE_INDEX_DATA, buffer.get(), "Namespace should be INDEX_DATA");
        assertEquals(tableId, buffer.getLong(), "TableID should match");
        assertEquals(indexId, buffer.getLong(), "IndexID should match");
    }
    
    @Test
    public void testIndexKeyTimestampInversion() {
        // Verify that newer timestamps sort before older timestamps (MVCC)
        long tableId = 1;
        long indexId = 1;
        List<Object> indexValues = Arrays.asList(100);
        List<Object> pkValues = Arrays.asList(1);
        
        byte[] key1 = KeyEncoder.encodeIndexKey(tableId, indexId, indexValues, pkValues, 1000);
        byte[] key2 = KeyEncoder.encodeIndexKey(tableId, indexId, indexValues, pkValues, 2000);
        
        // Newer timestamp (2000) should sort BEFORE older timestamp (1000)
        assertTrue(compare(key2, key1) < 0,
            "Newer timestamp should sort before older timestamp (MVCC)");
    }
    
    // ==================== Null Handling Tests ====================
    
    @Test
    public void testNullSortsFirst() {
        byte[] nullVal = KeyEncoder.encodeValue(null);
        byte[] intVal = KeyEncoder.encodeValue(0);
        byte[] stringVal = KeyEncoder.encodeValue("");
        
        assertTrue(compare(nullVal, intVal) < 0, "NULL should sort before INT");
        assertTrue(compare(nullVal, stringVal) < 0, "NULL should sort before STRING");
    }
    
    @Test
    public void testNullInCompositeKey() {
        List<Object> pk1 = Arrays.asList(1, null);
        List<Object> pk2 = Arrays.asList(1, "alice");
        List<Object> pk3 = Arrays.asList(2, null);
        
        byte[] key1 = KeyEncoder.encodeTableDataKey(1, pk1, 0);
        byte[] key2 = KeyEncoder.encodeTableDataKey(1, pk2, 0);
        byte[] key3 = KeyEncoder.encodeTableDataKey(1, pk3, 0);
        
        assertTrue(compare(key1, key2) < 0, "(1,NULL) should sort before (1,'alice')");
        assertTrue(compare(key2, key3) < 0, "(1,'alice') should sort before (2,NULL)");
    }
    
    // ==================== Helper Methods ====================
    
    /**
     * Compare two byte arrays lexicographically (like Cassandra does)
     */
    private int compare(byte[] a, byte[] b) {
        int minLength = Math.min(a.length, b.length);
        for (int i = 0; i < minLength; i++) {
            int cmp = Byte.toUnsignedInt(a[i]) - Byte.toUnsignedInt(b[i]);
            if (cmp != 0) {
                return cmp;
            }
        }
        return a.length - b.length;
    }
}
