package com.geico.poc.cassandrasql;

import org.apache.calcite.sql.*;
import org.apache.calcite.sql.parser.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Simple test to verify HAVING clause parsing with Calcite
 */
public class HavingParseTest {
    
    @Test
    public void testCalciteHavingParsing() throws Exception {
        String sql = "SELECT user_id, COUNT(*) as order_count FROM orders GROUP BY user_id HAVING COUNT(*) > 2";
        
        SqlParser parser = SqlParser.create(sql);
        SqlNode node = parser.parseStmt();
        
        assertTrue(node instanceof SqlSelect, "Should parse as SELECT");
        
        SqlSelect select = (SqlSelect) node;
        
        // Check GROUP BY
        assertNotNull(select.getGroup(), "Should have GROUP BY");
        assertEquals(1, select.getGroup().size(), "Should have 1 GROUP BY column");
        
        // Check HAVING
        assertNotNull(select.getHaving(), "Should have HAVING clause");
        String havingStr = select.getHaving().toString();
        System.out.println("HAVING clause: " + havingStr);
        
        assertTrue(havingStr.contains(">") || havingStr.contains("COUNT"), 
                  "HAVING should contain comparison");
    }
}



