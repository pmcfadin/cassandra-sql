package com.geico.poc.cassandrasql;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CalciteParser - specifically table name extraction
 */
public class CalciteParserTest {
    
    @Test
    public void testExtractTableNameFromCreateIndex() throws Exception {
        CalciteParser parser = new CalciteParser();
        Method method = CalciteParser.class.getDeclaredMethod("extractTableNameFromIndex", String.class);
        method.setAccessible(true);
        
        // Test basic CREATE INDEX
        String result1 = (String) method.invoke(parser, "CREATE INDEX idx_v ON test(v)");
        assertEquals("test", result1, "Should extract 'test' from 'CREATE INDEX idx_v ON test(v)'");
        
        // Test CREATE INDEX with whitespace
        String result2 = (String) method.invoke(parser, "CREATE INDEX idx_v ON test (v)");
        assertEquals("test", result2, "Should extract 'test' from 'CREATE INDEX idx_v ON test (v)'");
        
        // Test CREATE UNIQUE INDEX
        String result3 = (String) method.invoke(parser, "CREATE UNIQUE INDEX idx_v ON test(v)");
        assertEquals("test", result3, "Should extract 'test' from 'CREATE UNIQUE INDEX idx_v ON test(v)'");
        
        // Test with multiple columns
        String result4 = (String) method.invoke(parser, "CREATE INDEX idx_multi ON test(col1, col2, col3)");
        assertEquals("test", result4, "Should extract 'test' from multi-column index");
        
        // Test with semicolon
        String result5 = (String) method.invoke(parser, "CREATE INDEX idx_v ON test(v);");
        assertEquals("test", result5, "Should extract 'test' and ignore semicolon");
        
        // Test edge case that was failing before the fix
        String result6 = (String) method.invoke(parser, "create index idx_v on test(v);");
        assertEquals("test", result6, "Should extract 'test' (lowercase)");
    }
    
    // Removed testExtractTableNameFromDrop() - method no longer exists in CalciteParser
}

