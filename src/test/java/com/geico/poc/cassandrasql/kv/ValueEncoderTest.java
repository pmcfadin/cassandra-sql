package com.geico.poc.cassandrasql.kv;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ValueEncoder
 */
public class ValueEncoderTest {
    
    @Test
    public void testEncodeDecodeRow_Integers() {
        List<Object> values = Arrays.asList(1, 2, 3);
        List<Class<?>> types = Arrays.asList(Integer.class, Integer.class, Integer.class);
        
        byte[] encoded = ValueEncoder.encodeRow(values);
        List<Object> decoded = ValueEncoder.decodeRow(encoded, types);
        
        assertEquals(3, decoded.size());
        assertEquals(1, decoded.get(0));
        assertEquals(2, decoded.get(1));
        assertEquals(3, decoded.get(2));
    }
    
    @Test
    public void testEncodeDecodeRow_MixedTypes() {
        List<Object> values = Arrays.asList(42, "Alice", true, 3.14);
        List<Class<?>> types = Arrays.asList(Integer.class, String.class, Boolean.class, Double.class);
        
        byte[] encoded = ValueEncoder.encodeRow(values);
        List<Object> decoded = ValueEncoder.decodeRow(encoded, types);
        
        assertEquals(4, decoded.size());
        assertEquals(42, decoded.get(0));
        assertEquals("Alice", decoded.get(1));
        assertEquals(true, decoded.get(2));
        assertEquals(3.14, (Double) decoded.get(3), 0.0001);
    }
    
    @Test
    public void testEncodeDecodeRow_WithNulls() {
        List<Object> values = Arrays.asList(1, null, "test", null);
        List<Class<?>> types = Arrays.asList(Integer.class, String.class, String.class, Integer.class);
        
        byte[] encoded = ValueEncoder.encodeRow(values);
        List<Object> decoded = ValueEncoder.decodeRow(encoded, types);
        
        assertEquals(4, decoded.size());
        assertEquals(1, decoded.get(0));
        assertNull(decoded.get(1));
        assertEquals("test", decoded.get(2));
        assertNull(decoded.get(3));
    }
    
    @Test
    public void testEncodeDecodeRow_EmptyStrings() {
        List<Object> values = Arrays.asList("", "test", "");
        List<Class<?>> types = Arrays.asList(String.class, String.class, String.class);
        
        byte[] encoded = ValueEncoder.encodeRow(values);
        List<Object> decoded = ValueEncoder.decodeRow(encoded, types);
        
        assertEquals(3, decoded.size());
        assertEquals("", decoded.get(0));
        assertEquals("test", decoded.get(1));
        assertEquals("", decoded.get(2));
    }
    
    @Test
    public void testEncodeDecodeRow_Longs() {
        List<Object> values = Arrays.asList(Long.MAX_VALUE, Long.MIN_VALUE, 0L);
        List<Class<?>> types = Arrays.asList(Long.class, Long.class, Long.class);
        
        byte[] encoded = ValueEncoder.encodeRow(values);
        List<Object> decoded = ValueEncoder.decodeRow(encoded, types);
        
        assertEquals(3, decoded.size());
        assertEquals(Long.MAX_VALUE, decoded.get(0));
        assertEquals(Long.MIN_VALUE, decoded.get(1));
        assertEquals(0L, decoded.get(2));
    }
    
    @Test
    public void testEncodeDecodeRow_Floats() {
        List<Object> values = Arrays.asList(3.14f, -2.5f, 0.0f);
        List<Class<?>> types = Arrays.asList(Float.class, Float.class, Float.class);
        
        byte[] encoded = ValueEncoder.encodeRow(values);
        List<Object> decoded = ValueEncoder.decodeRow(encoded, types);
        
        assertEquals(3, decoded.size());
        assertEquals(3.14f, (Float) decoded.get(0), 0.0001f);
        assertEquals(-2.5f, (Float) decoded.get(1), 0.0001f);
        assertEquals(0.0f, (Float) decoded.get(2), 0.0001f);
    }
    
    @Test
    public void testEncodeDecodeRow_Bytes() {
        byte[] data1 = new byte[]{1, 2, 3};
        byte[] data2 = new byte[]{4, 5, 6};
        List<Object> values = Arrays.asList(data1, data2);
        List<Class<?>> types = Arrays.asList(byte[].class, byte[].class);
        
        byte[] encoded = ValueEncoder.encodeRow(values);
        List<Object> decoded = ValueEncoder.decodeRow(encoded, types);
        
        assertEquals(2, decoded.size());
        assertArrayEquals(data1, (byte[]) decoded.get(0));
        assertArrayEquals(data2, (byte[]) decoded.get(1));
    }
    
    @Test
    public void testEncodeDecodeRow_EmptyRow() {
        List<Object> values = Arrays.asList();
        List<Class<?>> types = Arrays.asList();
        
        byte[] encoded = ValueEncoder.encodeRow(values);
        List<Object> decoded = ValueEncoder.decodeRow(encoded, types);
        
        assertEquals(0, decoded.size());
    }
    
    @Test
    public void testEncodeDecodeRow_SingleColumn() {
        List<Object> values = Arrays.asList("single");
        List<Class<?>> types = Arrays.asList(String.class);
        
        byte[] encoded = ValueEncoder.encodeRow(values);
        List<Object> decoded = ValueEncoder.decodeRow(encoded, types);
        
        assertEquals(1, decoded.size());
        assertEquals("single", decoded.get(0));
    }
    
    @Test
    public void testEncodeDecodeRow_LargeStrings() {
        String largeString = "x".repeat(10000);
        List<Object> values = Arrays.asList(largeString);
        List<Class<?>> types = Arrays.asList(String.class);
        
        byte[] encoded = ValueEncoder.encodeRow(values);
        List<Object> decoded = ValueEncoder.decodeRow(encoded, types);
        
        assertEquals(1, decoded.size());
        assertEquals(largeString, decoded.get(0));
    }
    
    @Test
    public void testEncodeDecodeRow_SpecialCharacters() {
        List<Object> values = Arrays.asList("hello\nworld", "tab\there", "quote\"test");
        List<Class<?>> types = Arrays.asList(String.class, String.class, String.class);
        
        byte[] encoded = ValueEncoder.encodeRow(values);
        List<Object> decoded = ValueEncoder.decodeRow(encoded, types);
        
        assertEquals(3, decoded.size());
        assertEquals("hello\nworld", decoded.get(0));
        assertEquals("tab\there", decoded.get(1));
        assertEquals("quote\"test", decoded.get(2));
    }
    
    @Test
    public void testEncodeIndexEntry() {
        byte[] encoded = ValueEncoder.encodeIndexEntry();
        
        // Should have version and flags
        assertTrue(encoded.length >= 2);
        assertEquals(ValueEncoder.VERSION_1, encoded[0]);
    }
    
    @Test
    public void testVersionCheck() {
        List<Object> values = Arrays.asList(1, 2, 3);
        byte[] encoded = ValueEncoder.encodeRow(values);
        
        assertEquals(ValueEncoder.VERSION_1, ValueEncoder.getVersion(encoded));
    }
    
    @Test
    public void testFlagsCheck() {
        List<Object> values = Arrays.asList(1, 2, 3);
        
        // Without compression/encryption
        byte[] encoded1 = ValueEncoder.encodeRow(values, false, false);
        assertFalse(ValueEncoder.isCompressed(encoded1));
        assertFalse(ValueEncoder.isEncrypted(encoded1));
        
        // With compression
        byte[] encoded2 = ValueEncoder.encodeRow(values, true, false);
        assertTrue(ValueEncoder.isCompressed(encoded2));
        assertFalse(ValueEncoder.isEncrypted(encoded2));
        
        // With encryption
        byte[] encoded3 = ValueEncoder.encodeRow(values, false, true);
        assertFalse(ValueEncoder.isCompressed(encoded3));
        assertTrue(ValueEncoder.isEncrypted(encoded3));
        
        // With both
        byte[] encoded4 = ValueEncoder.encodeRow(values, true, true);
        assertTrue(ValueEncoder.isCompressed(encoded4));
        assertTrue(ValueEncoder.isEncrypted(encoded4));
    }
    
    @Test
    public void testEncodeDecodeRow_AllNulls() {
        List<Object> values = Arrays.asList(null, null, null);
        List<Class<?>> types = Arrays.asList(Integer.class, String.class, Boolean.class);
        
        byte[] encoded = ValueEncoder.encodeRow(values);
        List<Object> decoded = ValueEncoder.decodeRow(encoded, types);
        
        assertEquals(3, decoded.size());
        assertNull(decoded.get(0));
        assertNull(decoded.get(1));
        assertNull(decoded.get(2));
    }
    
    @Test
    public void testEncodeDecodeRow_BooleanValues() {
        List<Object> values = Arrays.asList(true, false, true, false);
        List<Class<?>> types = Arrays.asList(Boolean.class, Boolean.class, Boolean.class, Boolean.class);
        
        byte[] encoded = ValueEncoder.encodeRow(values);
        List<Object> decoded = ValueEncoder.decodeRow(encoded, types);
        
        assertEquals(4, decoded.size());
        assertEquals(true, decoded.get(0));
        assertEquals(false, decoded.get(1));
        assertEquals(true, decoded.get(2));
        assertEquals(false, decoded.get(3));
    }
    
    @Test
    public void testEncodeDecodeRow_NegativeNumbers() {
        List<Object> values = Arrays.asList(-1, -100, -1000);
        List<Class<?>> types = Arrays.asList(Integer.class, Integer.class, Integer.class);
        
        byte[] encoded = ValueEncoder.encodeRow(values);
        List<Object> decoded = ValueEncoder.decodeRow(encoded, types);
        
        assertEquals(3, decoded.size());
        assertEquals(-1, decoded.get(0));
        assertEquals(-100, decoded.get(1));
        assertEquals(-1000, decoded.get(2));
    }
}



