package com.geico.poc.cassandrasql.kv;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test to verify that integer key encoding produces byte-order-comparable keys
 * that maintain proper numeric ordering.
 * 
 * Critical for ByteOrderedPartitioner in Cassandra.
 */
public class IntegerKeyOrderingTest {
    
    /**
     * Test the specific example from the user's question:
     * Will 1 < 9 < 10 < 19 < 100 < 1000 < 9000?
     */
    @Test
    public void testSpecificIntegerOrdering() {
        List<Integer> values = Arrays.asList(1, 9, 10, 19, 100, 1000, 9000);
        
        for (int i = 0; i < values.size() - 1; i++) {
            int val1 = values.get(i);
            int val2 = values.get(i + 1);
            
            byte[] encoded1 = KeyEncoder.encodeValue(val1);
            byte[] encoded2 = KeyEncoder.encodeValue(val2);
            
            int comparison = compareBytes(encoded1, encoded2);
            
            assertTrue(comparison < 0,
                String.format("Encoded value for %d should sort before %d, but comparison was %d", 
                    val1, val2, comparison));
            
            // Also verify the actual byte values for debugging
            System.out.printf("Value %d: %s\n", val1, bytesToHex(encoded1));
            System.out.printf("Value %d: %s\n", val2, bytesToHex(encoded2));
            System.out.printf("Comparison: %d < 0? %s\n\n", comparison, comparison < 0);
        }
    }
    
    /**
     * Test a broader range of positive integers
     */
    @Test
    public void testPositiveIntegerOrdering() {
        List<Integer> values = Arrays.asList(
            0, 1, 2, 9, 10, 11, 19, 20, 99, 100, 101, 999, 1000, 1001, 9999, 10000
        );
        
        for (int i = 0; i < values.size() - 1; i++) {
            int val1 = values.get(i);
            int val2 = values.get(i + 1);
            
            byte[] encoded1 = KeyEncoder.encodeValue(val1);
            byte[] encoded2 = KeyEncoder.encodeValue(val2);
            
            assertTrue(compareBytes(encoded1, encoded2) < 0,
                String.format("%d should sort before %d", val1, val2));
        }
    }
    
    /**
     * Test negative integers (should sort before positive)
     */
    @Test
    public void testNegativeIntegerOrdering() {
        List<Integer> values = Arrays.asList(-1000, -100, -10, -1, 0, 1, 10, 100, 1000);
        
        for (int i = 0; i < values.size() - 1; i++) {
            int val1 = values.get(i);
            int val2 = values.get(i + 1);
            
            byte[] encoded1 = KeyEncoder.encodeValue(val1);
            byte[] encoded2 = KeyEncoder.encodeValue(val2);
            
            assertTrue(compareBytes(encoded1, encoded2) < 0,
                String.format("%d should sort before %d", val1, val2));
        }
    }
    
    /**
     * Test that integer encoding preserves order across the full range
     */
    @Test
    public void testFullIntegerRangeOrdering() {
        List<Integer> values = Arrays.asList(
            Integer.MIN_VALUE,
            -1000000,
            -1000,
            -100,
            -1,
            0,
            1,
            100,
            1000,
            1000000,
            Integer.MAX_VALUE
        );
        
        for (int i = 0; i < values.size() - 1; i++) {
            int val1 = values.get(i);
            int val2 = values.get(i + 1);
            
            byte[] encoded1 = KeyEncoder.encodeValue(val1);
            byte[] encoded2 = KeyEncoder.encodeValue(val2);
            
            assertTrue(compareBytes(encoded1, encoded2) < 0,
                String.format("%d should sort before %d", val1, val2));
        }
    }
    
    /**
     * Test that integer keys in table data keys maintain ordering
     */
    @Test
    public void testTableDataKeyOrdering() {
        long tableId = 1;
        List<Integer> pkValues = Arrays.asList(1, 9, 10, 19, 100, 1000, 9000);
        
        for (int i = 0; i < pkValues.size() - 1; i++) {
            int pk1 = pkValues.get(i);
            int pk2 = pkValues.get(i + 1);
            
            byte[] key1 = KeyEncoder.encodeTableDataKey(tableId, Arrays.asList(pk1), 0);
            byte[] key2 = KeyEncoder.encodeTableDataKey(tableId, Arrays.asList(pk2), 0);
            
            assertTrue(compareBytes(key1, key2) < 0,
                String.format("Table key with PK=%d should sort before PK=%d", pk1, pk2));
        }
    }
    
    /**
     * Test multi-column integer primary keys
     */
    @Test
    public void testMultiColumnIntegerKeyOrdering() {
        long tableId = 1;
        
        // Test: (1, 1) < (1, 2) < (1, 10) < (2, 1) < (10, 1)
        List<List<Object>> pkValues = Arrays.asList(
            Arrays.asList(1, 1),
            Arrays.asList(1, 2),
            Arrays.asList(1, 10),
            Arrays.asList(2, 1),
            Arrays.asList(10, 1)
        );
        
        for (int i = 0; i < pkValues.size() - 1; i++) {
            List<Object> pk1 = pkValues.get(i);
            List<Object> pk2 = pkValues.get(i + 1);
            
            byte[] key1 = KeyEncoder.encodeTableDataKey(tableId, pk1, 0);
            byte[] key2 = KeyEncoder.encodeTableDataKey(tableId, pk2, 0);
            
            assertTrue(compareBytes(key1, key2) < 0,
                String.format("Table key with PK=%s should sort before PK=%s", pk1, pk2));
        }
    }
    
    /**
     * Verify the encoding algorithm by checking actual byte values
     */
    @Test
    public void testIntegerEncodingAlgorithm() {
        // The encoding is: value ^ 0x80000000, stored as big-endian 4-byte integer
        // This flips the sign bit so that:
        // - Negative numbers become 0x00000000-0x7FFFFFFF (sort first)
        // - Positive numbers become 0x80000000-0xFFFFFFFF (sort after negatives)
        
        // Test value 1
        int val1 = 1;
        byte[] encoded1 = KeyEncoder.encodeValue(val1);
        // Should be: [TYPE_INT=0x01][(1 ^ 0x80000000) as 4 bytes big-endian]
        // 1 ^ 0x80000000 = 0x80000001
        // Big-endian: 0x80, 0x00, 0x00, 0x01
        assertEquals(5, encoded1.length, "Encoded integer should be 5 bytes (1 type + 4 value)");
        assertEquals(0x01, encoded1[0] & 0xFF, "Type prefix should be TYPE_INT (0x01)");
        
        // Extract the 4-byte integer value
        ByteBuffer buffer = ByteBuffer.wrap(encoded1);
        buffer.get(); // Skip type prefix
        int encodedValue = buffer.getInt();
        int expectedEncoded = val1 ^ 0x80000000;
        assertEquals(expectedEncoded, encodedValue, 
            "Encoded value should be original value XOR 0x80000000");
        
        // Verify decode works
        buffer.rewind();
        buffer.get(); // Skip type prefix
        int decoded = KeyEncoder.decodeInt(buffer);
        assertEquals(val1, decoded, "Decode should recover original value");
    }
    
    /**
     * Compare two byte arrays lexicographically (unsigned byte comparison)
     * This matches how Cassandra's ByteOrderedPartitioner compares keys
     */
    private int compareBytes(byte[] a, byte[] b) {
        int minLength = Math.min(a.length, b.length);
        for (int i = 0; i < minLength; i++) {
            // Compare as unsigned bytes
            int cmp = Byte.toUnsignedInt(a[i]) - Byte.toUnsignedInt(b[i]);
            if (cmp != 0) {
                return cmp;
            }
        }
        return a.length - b.length;
    }
    
    /**
     * Convert byte array to hex string for debugging
     */
    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b & 0xFF));
        }
        return sb.toString().trim();
    }
}

