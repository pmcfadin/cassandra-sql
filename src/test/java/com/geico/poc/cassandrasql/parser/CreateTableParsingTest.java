package com.geico.poc.cassandrasql.parser;

import com.geico.poc.cassandrasql.CalciteParser;
import com.geico.poc.cassandrasql.ParsedQuery;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test CREATE TABLE parsing, especially edge cases with table name extraction.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class CreateTableParsingTest {

    private CalciteParser parser;

    @BeforeEach
    public void setup() {
        parser = new CalciteParser();
    }

    // ========================================
    // Test 1: CREATE TABLE with space before parenthesis
    // ========================================

    @Test
    @Order(1)
    public void testCreateTableWithSpace() throws Exception {
        String sql = "CREATE TABLE test_table (id INT PRIMARY KEY, name TEXT)";
        ParsedQuery query = parser.parse(sql);
        
        assertEquals(ParsedQuery.Type.CREATE_TABLE, query.getType());
        assertEquals("test_table", query.getTableName());
    }

    // ========================================
    // Test 2: CREATE TABLE without space before parenthesis (pgbench format)
    // ========================================

    @Test
    @Order(2)
    public void testCreateTableWithoutSpace() throws Exception {
        String sql = "create table pgbench_history(tid int,bid int,aid int,delta int,mtime timestamp,filler char(22))";
        ParsedQuery query = parser.parse(sql);
        
        assertEquals(ParsedQuery.Type.CREATE_TABLE, query.getType());
        assertEquals("pgbench_history", query.getTableName(), 
            "Table name should be 'pgbench_history', not 'pgbench_historytid'");
    }

    // ========================================
    // Test 3: CREATE TABLE IF NOT EXISTS with space
    // ========================================

    @Test
    @Order(3)
    public void testCreateTableIfNotExistsWithSpace() throws Exception {
        String sql = "CREATE TABLE IF NOT EXISTS test_table (id INT PRIMARY KEY)";
        ParsedQuery query = parser.parse(sql);
        
        assertEquals(ParsedQuery.Type.CREATE_TABLE, query.getType());
        assertEquals("test_table", query.getTableName());
    }

    // ========================================
    // Test 4: CREATE TABLE IF NOT EXISTS without space
    // ========================================

    @Test
    @Order(4)
    public void testCreateTableIfNotExistsWithoutSpace() throws Exception {
        String sql = "CREATE TABLE IF NOT EXISTS pgbench_accounts(aid int,bid int,abalance int,filler char(84))";
        ParsedQuery query = parser.parse(sql);
        
        assertEquals(ParsedQuery.Type.CREATE_TABLE, query.getType());
        assertEquals("pgbench_accounts", query.getTableName(),
            "Table name should be 'pgbench_accounts', not 'pgbench_accountsaid'");
    }

    // ========================================
    // Test 5: All pgbench tables
    // ========================================

    @Test
    @Order(5)
    public void testAllPgBenchTables() throws Exception {
        String[] sqls = {
            "create table pgbench_accounts(aid int,bid int,abalance int,filler char(84))",
            "create table pgbench_branches(bid int,bbalance int,filler char(88))",
            "create table pgbench_history(tid int,bid int,aid int,delta int,mtime timestamp,filler char(22))",
            "create table pgbench_tellers(tid int,bid int,tbalance int,filler char(84))"
        };
        
        String[] expectedNames = {
            "pgbench_accounts",
            "pgbench_branches",
            "pgbench_history",
            "pgbench_tellers"
        };
        
        for (int i = 0; i < sqls.length; i++) {
            ParsedQuery query = parser.parse(sqls[i]);
            assertEquals(ParsedQuery.Type.CREATE_TABLE, query.getType());
            assertEquals(expectedNames[i], query.getTableName(),
                "Failed for SQL: " + sqls[i]);
        }
    }

    // ========================================
    // Test 6: Mixed case
    // ========================================

    @Test
    @Order(6)
    public void testMixedCase() throws Exception {
        String sql = "CREATE TABLE MyTable(id INT PRIMARY KEY)";
        ParsedQuery query = parser.parse(sql);
        
        assertEquals(ParsedQuery.Type.CREATE_TABLE, query.getType());
        assertEquals("MyTable", query.getTableName());
    }

    // ========================================
    // Test 7: Table name with underscores
    // ========================================

    @Test
    @Order(7)
    public void testTableNameWithUnderscores() throws Exception {
        String sql = "CREATE TABLE my_test_table_123(id INT PRIMARY KEY)";
        ParsedQuery query = parser.parse(sql);
        
        assertEquals(ParsedQuery.Type.CREATE_TABLE, query.getType());
        assertEquals("my_test_table_123", query.getTableName());
    }

    // ========================================
    // Test 8: Normal whitespace variations (extra spaces not supported by Calcite)
    // ========================================

    @Test
    @Order(8)
    public void testNormalWhitespace() throws Exception {
        String[] sqls = {
            "CREATE TABLE test_table(id INT)",
            "CREATE TABLE test_table (id INT)",
            "CREATE TABLE test_table  (  id INT  )"
        };
        
        for (String sql : sqls) {
            ParsedQuery query = parser.parse(sql);
            assertEquals("test_table", query.getTableName(),
                "Failed for SQL: " + sql);
        }
    }
}

