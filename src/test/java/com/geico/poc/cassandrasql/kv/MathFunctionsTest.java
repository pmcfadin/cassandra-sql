package com.geico.poc.cassandrasql.kv;

import org.junit.jupiter.api.*;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for mathematical functions
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class MathFunctionsTest {
    
    // ========== Basic Math Functions ==========
    
    @Test
    @Order(1)
    public void testAbs() {
        assertEquals(5, MathFunctions.abs(-5));
        assertEquals(5L, MathFunctions.abs(-5L));
        assertEquals(5.5, (Double) MathFunctions.abs(-5.5), 0.001);
        assertNull(MathFunctions.abs(null));
        
        BigDecimal bd = new BigDecimal("-123.45");
        Object result = MathFunctions.abs(bd);
        assertTrue(result instanceof BigDecimal);
        assertEquals(new BigDecimal("123.45"), result);
    }
    
    @Test
    @Order(2)
    public void testCeil() {
        assertEquals(6.0, (Double) MathFunctions.ceil(5.3), 0.001);
        assertEquals(-5.0, (Double) MathFunctions.ceil(-5.7), 0.001);
        assertNull(MathFunctions.ceil(null));
        
        BigDecimal bd = new BigDecimal("5.3");
        Object result = MathFunctions.ceil(bd);
        assertTrue(result instanceof BigDecimal);
    }
    
    @Test
    @Order(3)
    public void testFloor() {
        assertEquals(5.0, (Double) MathFunctions.floor(5.7), 0.001);
        assertEquals(-6.0, (Double) MathFunctions.floor(-5.3), 0.001);
        assertNull(MathFunctions.floor(null));
    }
    
    @Test
    @Order(4)
    public void testRound() {
        assertEquals(5.0, (Double) MathFunctions.round(5.4), 0.001);
        assertEquals(6.0, (Double) MathFunctions.round(5.6), 0.001);
        assertEquals(123.46, (Double) MathFunctions.round(123.456, 2), 0.001);
        assertNull(MathFunctions.round(null));
    }
    
    @Test
    @Order(5)
    public void testTrunc() {
        assertEquals(5.0, (Double) MathFunctions.trunc(5.9), 0.001);
        assertEquals(-5.0, (Double) MathFunctions.trunc(-5.9), 0.001);
        assertEquals(123.45, (Double) MathFunctions.trunc(123.456, 2), 0.001);
        assertNull(MathFunctions.trunc(null));
    }
    
    // ========== Trigonometric Functions ==========
    
    @Test
    @Order(10)
    public void testSin() {
        assertEquals(0.0, MathFunctions.sin(0), 0.001);
        assertEquals(1.0, MathFunctions.sin(Math.PI / 2), 0.001);
        assertNull(MathFunctions.sin(null));
    }
    
    @Test
    @Order(11)
    public void testCos() {
        assertEquals(1.0, MathFunctions.cos(0), 0.001);
        assertEquals(0.0, MathFunctions.cos(Math.PI / 2), 0.001);
        assertNull(MathFunctions.cos(null));
    }
    
    @Test
    @Order(12)
    public void testTan() {
        assertEquals(0.0, MathFunctions.tan(0), 0.001);
        assertNull(MathFunctions.tan(null));
    }
    
    @Test
    @Order(13)
    public void testAsin() {
        assertEquals(0.0, MathFunctions.asin(0), 0.001);
        assertEquals(Math.PI / 2, MathFunctions.asin(1), 0.001);
        assertNull(MathFunctions.asin(null));
    }
    
    @Test
    @Order(14)
    public void testAcos() {
        assertEquals(Math.PI / 2, MathFunctions.acos(0), 0.001);
        assertEquals(0.0, MathFunctions.acos(1), 0.001);
        assertNull(MathFunctions.acos(null));
    }
    
    @Test
    @Order(15)
    public void testAtan() {
        assertEquals(0.0, MathFunctions.atan(0), 0.001);
        assertNull(MathFunctions.atan(null));
    }
    
    @Test
    @Order(16)
    public void testAtan2() {
        assertEquals(Math.PI / 4, MathFunctions.atan2(1, 1), 0.001);
        assertNull(MathFunctions.atan2(null, 1));
        assertNull(MathFunctions.atan2(1, null));
    }
    
    @Test
    @Order(17)
    public void testDegrees() {
        assertEquals(0.0, MathFunctions.degrees(0), 0.001);
        assertEquals(180.0, MathFunctions.degrees(Math.PI), 0.001);
        assertNull(MathFunctions.degrees(null));
    }
    
    @Test
    @Order(18)
    public void testRadians() {
        assertEquals(0.0, MathFunctions.radians(0), 0.001);
        assertEquals(Math.PI, MathFunctions.radians(180), 0.001);
        assertNull(MathFunctions.radians(null));
    }
    
    // ========== Exponential and Logarithmic Functions ==========
    
    @Test
    @Order(20)
    public void testExp() {
        assertEquals(1.0, MathFunctions.exp(0), 0.001);
        assertEquals(Math.E, MathFunctions.exp(1), 0.001);
        assertNull(MathFunctions.exp(null));
    }
    
    @Test
    @Order(21)
    public void testLn() {
        assertEquals(0.0, MathFunctions.ln(1), 0.001);
        assertEquals(1.0, MathFunctions.ln(Math.E), 0.001);
        assertNull(MathFunctions.ln(null));
        
        // Test error for non-positive numbers
        assertThrows(IllegalArgumentException.class, () -> MathFunctions.ln(0));
        assertThrows(IllegalArgumentException.class, () -> MathFunctions.ln(-1));
    }
    
    @Test
    @Order(22)
    public void testLog() {
        assertEquals(0.0, MathFunctions.log(1), 0.001);
        assertEquals(1.0, MathFunctions.log(10), 0.001);
        assertEquals(2.0, MathFunctions.log(100), 0.001);
        assertNull(MathFunctions.log(null));
        
        // Test with custom base
        assertEquals(2.0, MathFunctions.log(2, 4), 0.001);
        assertEquals(3.0, MathFunctions.log(2, 8), 0.001);
    }
    
    @Test
    @Order(23)
    public void testPower() {
        assertEquals(8.0, (Double) MathFunctions.power(2, 3), 0.001);
        assertEquals(1.0, (Double) MathFunctions.power(5, 0), 0.001);
        assertEquals(0.25, (Double) MathFunctions.power(2, -2), 0.001);
        assertNull(MathFunctions.power(null, 2));
        assertNull(MathFunctions.power(2, null));
        
        // Test with BigDecimal
        BigDecimal bd = new BigDecimal("2");
        Object result = MathFunctions.power(bd, 3);
        assertTrue(result instanceof BigDecimal);
    }
    
    @Test
    @Order(24)
    public void testSqrt() {
        assertEquals(0.0, MathFunctions.sqrt(0), 0.001);
        assertEquals(2.0, MathFunctions.sqrt(4), 0.001);
        assertEquals(3.0, MathFunctions.sqrt(9), 0.001);
        assertNull(MathFunctions.sqrt(null));
        
        // Test error for negative numbers
        assertThrows(IllegalArgumentException.class, () -> MathFunctions.sqrt(-1));
    }
    
    @Test
    @Order(25)
    public void testCbrt() {
        assertEquals(0.0, MathFunctions.cbrt(0), 0.001);
        assertEquals(2.0, MathFunctions.cbrt(8), 0.001);
        assertEquals(-2.0, MathFunctions.cbrt(-8), 0.001);
        assertNull(MathFunctions.cbrt(null));
    }
    
    // ========== Other Math Functions ==========
    
    @Test
    @Order(30)
    public void testMod() {
        assertEquals(1, MathFunctions.mod(10, 3));
        assertEquals(2L, MathFunctions.mod(17L, 5L));
        assertEquals(1.5, (Double) MathFunctions.mod(7.5, 3.0), 0.001);
        assertNull(MathFunctions.mod(null, 3));
        assertNull(MathFunctions.mod(3, null));
    }
    
    @Test
    @Order(31)
    public void testSign() {
        assertEquals(1, MathFunctions.sign(42));
        assertEquals(-1, MathFunctions.sign(-42));
        assertEquals(0, MathFunctions.sign(0));
        assertNull(MathFunctions.sign(null));
        
        BigDecimal bd = new BigDecimal("-123.45");
        assertEquals(-1, MathFunctions.sign(bd));
    }
    
    @Test
    @Order(32)
    public void testRandom() {
        Double random = MathFunctions.random();
        assertNotNull(random);
        assertTrue(random >= 0.0 && random < 1.0);
    }
    
    @Test
    @Order(33)
    public void testPi() {
        assertEquals(Math.PI, MathFunctions.pi(), 0.0001);
    }
    
    // ========== Helper Methods ==========
    
    @Test
    @Order(40)
    public void testToBigDecimal() {
        assertNull(MathFunctions.toBigDecimal(null));
        
        assertEquals(new BigDecimal("123"), MathFunctions.toBigDecimal(123));
        assertEquals(new BigDecimal("123"), MathFunctions.toBigDecimal(123L));
        assertEquals(BigDecimal.valueOf(123.45), MathFunctions.toBigDecimal(123.45));
        assertEquals(new BigDecimal("99.99"), MathFunctions.toBigDecimal("99.99"));
        
        BigDecimal bd = new BigDecimal("456.78");
        assertSame(bd, MathFunctions.toBigDecimal(bd));
    }
}

