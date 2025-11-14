package com.geico.poc.cassandrasql.kv;

import com.geico.poc.cassandrasql.dto.QueryResponse;
import com.geico.poc.cassandrasql.QueryService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for DATE, TIME, TIMESTAMP, and INTERVAL types in KV mode
 */
@SpringBootTest
@TestPropertySource(properties = {
    "cassandra-sql.storage-mode=kv",
    "cassandra-sql.use-calcite-cbo=true"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class DateTimeTypeTest extends KvTestBase {

    @Autowired
    private QueryService queryService;

    @Test
    @Order(1)
    public void testDateTypeStorage() throws Exception {
        String tableName = uniqueTableName("date_test");

        // Drop table if it exists
        queryService.execute("DROP TABLE IF EXISTS " + tableName);

        // Create table with DATE column
        QueryResponse createResp = queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, birth_date DATE)",
            tableName
        ));
        assertNull(createResp.getError(), "CREATE TABLE failed: " + createResp.getError());

        Thread.sleep(200);

        // Insert a date
        QueryResponse insertResp = queryService.execute(String.format(
            "INSERT INTO %s (id, birth_date) VALUES (1, '2000-01-15')",
            tableName
        ));
        assertNull(insertResp.getError(), "INSERT failed: " + insertResp.getError());

        Thread.sleep(100);

        // Query the date
        QueryResponse selectResp = queryService.execute(String.format(
            "SELECT * FROM %s WHERE id = 1",
            tableName
        ));
        assertNull(selectResp.getError(), "SELECT failed: " + selectResp.getError());
        assertNotNull(selectResp.getRows());
        assertEquals(1, selectResp.getRows().size());

        Map<String, Object> row = selectResp.getRows().get(0);
        Object birthDate = row.get("birth_date");
        assertNotNull(birthDate, "birth_date should not be null");
        assertTrue(birthDate instanceof Date || birthDate instanceof java.util.Date, 
            "birth_date should be a Date type, but was: " + birthDate.getClass());

        // Cleanup
        queryService.execute("DROP TABLE " + tableName);
    }

    @Test
    @Order(2)
    public void testTimeTypeStorage() throws Exception {
        String tableName = uniqueTableName("time_test");

        // Drop table if it exists
        queryService.execute("DROP TABLE IF EXISTS " + tableName);

        // Create table with TIME column
        QueryResponse createResp = queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, meeting_time TIME)",
            tableName
        ));
        assertNull(createResp.getError(), "CREATE TABLE failed: " + createResp.getError());

        Thread.sleep(200);

        // Insert a time
        QueryResponse insertResp = queryService.execute(String.format(
            "INSERT INTO %s (id, meeting_time) VALUES (1, '14:30:00')",
            tableName
        ));
        assertNull(insertResp.getError(), "INSERT failed: " + insertResp.getError());

        Thread.sleep(100);

        // Query the time
        QueryResponse selectResp = queryService.execute(String.format(
            "SELECT * FROM %s WHERE id = 1",
            tableName
        ));
        assertNull(selectResp.getError(), "SELECT failed: " + selectResp.getError());
        assertNotNull(selectResp.getRows());
        assertEquals(1, selectResp.getRows().size());

        Map<String, Object> row = selectResp.getRows().get(0);
        Object meetingTime = row.get("meeting_time");
        assertNotNull(meetingTime, "meeting_time should not be null");
        assertTrue(meetingTime instanceof Time || meetingTime instanceof java.util.Date, 
            "meeting_time should be a Time type, but was: " + meetingTime.getClass());

        // Cleanup
        queryService.execute("DROP TABLE " + tableName);
    }

    @Test
    @Order(3)
    public void testTimestampTypeStorage() throws Exception {
        String tableName = uniqueTableName("timestamp_test");

        // Drop table if it exists
        queryService.execute("DROP TABLE IF EXISTS " + tableName);

        // Create table with TIMESTAMP column
        QueryResponse createResp = queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, created_at TIMESTAMP)",
            tableName
        ));
        assertNull(createResp.getError(), "CREATE TABLE failed: " + createResp.getError());

        Thread.sleep(200);

        // Insert a timestamp
        QueryResponse insertResp = queryService.execute(String.format(
            "INSERT INTO %s (id, created_at) VALUES (1, '2024-01-15 14:30:45')",
            tableName
        ));
        assertNull(insertResp.getError(), "INSERT failed: " + insertResp.getError());

        Thread.sleep(100);

        // Query the timestamp
        QueryResponse selectResp = queryService.execute(String.format(
            "SELECT * FROM %s WHERE id = 1",
            tableName
        ));
        assertNull(selectResp.getError(), "SELECT failed: " + selectResp.getError());
        assertNotNull(selectResp.getRows());
        assertEquals(1, selectResp.getRows().size());

        Map<String, Object> row = selectResp.getRows().get(0);
        Object createdAt = row.get("created_at");
        assertNotNull(createdAt, "created_at should not be null");
        assertTrue(createdAt instanceof Timestamp || createdAt instanceof java.util.Date, 
            "created_at should be a Timestamp type, but was: " + createdAt.getClass());

        // Cleanup
        queryService.execute("DROP TABLE " + tableName);
    }

    @Test
    @Order(4)
    public void testNullDateTimeValues() throws Exception {
        String tableName = uniqueTableName("null_datetime_test");

        // Drop table if it exists
        queryService.execute("DROP TABLE IF EXISTS " + tableName);

        // Create table with nullable date/time columns
        QueryResponse createResp = queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, event_date DATE, event_time TIME, event_timestamp TIMESTAMP)",
            tableName
        ));
        assertNull(createResp.getError(), "CREATE TABLE failed: " + createResp.getError());

        Thread.sleep(200);

        // Insert row with NULL values
        QueryResponse insertResp = queryService.execute(String.format(
            "INSERT INTO %s (id, event_date, event_time, event_timestamp) VALUES (1, NULL, NULL, NULL)",
            tableName
        ));
        assertNull(insertResp.getError(), "INSERT failed: " + insertResp.getError());

        Thread.sleep(100);

        // Query the row
        QueryResponse selectResp = queryService.execute(String.format(
            "SELECT * FROM %s WHERE id = 1",
            tableName
        ));
        assertNull(selectResp.getError(), "SELECT failed: " + selectResp.getError());
        assertNotNull(selectResp.getRows());
        assertEquals(1, selectResp.getRows().size());

        Map<String, Object> row = selectResp.getRows().get(0);
        assertNull(row.get("event_date"), "event_date should be NULL");
        assertNull(row.get("event_time"), "event_time should be NULL");
        assertNull(row.get("event_timestamp"), "event_timestamp should be NULL");

        // Cleanup
        queryService.execute("DROP TABLE " + tableName);
    }

    @Test
    @Order(5)
    public void testMultipleDateTimeRows() throws Exception {
        String tableName = uniqueTableName("multi_datetime_test");

        // Drop table if it exists
        queryService.execute("DROP TABLE IF EXISTS " + tableName);

        // Create table
        QueryResponse createResp = queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, event_date DATE, event_timestamp TIMESTAMP)",
            tableName
        ));
        assertNull(createResp.getError(), "CREATE TABLE failed: " + createResp.getError());

        Thread.sleep(200);

        // Insert multiple rows
        for (int i = 1; i <= 5; i++) {
            queryService.execute(String.format(
                "INSERT INTO %s (id, event_date, event_timestamp) VALUES (%d, '2024-01-%02d', '2024-01-%02d 12:00:00')",
                tableName, i, i, i
            ));
        }

        Thread.sleep(200);

        // Query all rows
        QueryResponse selectResp = queryService.execute(String.format(
            "SELECT * FROM %s",
            tableName
        ));
        assertNull(selectResp.getError(), "SELECT failed: " + selectResp.getError());
        assertNotNull(selectResp.getRows());
        assertEquals(5, selectResp.getRows().size(), "Should have 5 rows");

        // Cleanup
        queryService.execute("DROP TABLE " + tableName);
    }

    @Test
    @Order(6)
    public void testUpdateDateTime() throws Exception {
        String tableName = uniqueTableName("update_datetime_test");

        // Drop table if it exists
        queryService.execute("DROP TABLE IF EXISTS " + tableName);

        // Create table
        QueryResponse createResp = queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, event_date DATE)",
            tableName
        ));
        assertNull(createResp.getError(), "CREATE TABLE failed: " + createResp.getError());

        Thread.sleep(200);

        // Insert a row
        queryService.execute(String.format(
            "INSERT INTO %s (id, event_date) VALUES (1, '2024-01-01')",
            tableName
        ));

        Thread.sleep(100);

        // Update the date
        QueryResponse updateResp = queryService.execute(String.format(
            "UPDATE %s SET event_date = '2024-12-31' WHERE id = 1",
            tableName
        ));
        assertNull(updateResp.getError(), "UPDATE failed: " + updateResp.getError());

        Thread.sleep(100);

        // Query the updated row
        QueryResponse selectResp = queryService.execute(String.format(
            "SELECT * FROM %s WHERE id = 1",
            tableName
        ));
        assertNull(selectResp.getError(), "SELECT failed: " + selectResp.getError());
        assertNotNull(selectResp.getRows());
        assertEquals(1, selectResp.getRows().size());

        Map<String, Object> row = selectResp.getRows().get(0);
        assertNotNull(row.get("event_date"), "event_date should not be null after update");

        // Cleanup
        queryService.execute("DROP TABLE " + tableName);
    }

    @Test
    @Order(7)
    public void testMixedDataTypes() throws Exception {
        String tableName = uniqueTableName("mixed_types_test");

        // Drop table if it exists
        queryService.execute("DROP TABLE IF EXISTS " + tableName);

        // Create table with mixed types
        QueryResponse createResp = queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, name TEXT, birth_date DATE, salary DOUBLE, active BOOLEAN, created_at TIMESTAMP)",
            tableName
        ));
        assertNull(createResp.getError(), "CREATE TABLE failed: " + createResp.getError());

        Thread.sleep(200);

        // Insert a row with mixed types
        QueryResponse insertResp = queryService.execute(String.format(
            "INSERT INTO %s (id, name, birth_date, salary, active, created_at) " +
            "VALUES (1, 'Alice', '1990-05-15', 75000.50, true, '2024-01-01 10:30:00')",
            tableName
        ));
        assertNull(insertResp.getError(), "INSERT failed: " + insertResp.getError());

        Thread.sleep(100);

        // Query the row
        QueryResponse selectResp = queryService.execute(String.format(
            "SELECT * FROM %s WHERE id = 1",
            tableName
        ));
        assertNull(selectResp.getError(), "SELECT failed: " + selectResp.getError());
        assertNotNull(selectResp.getRows());
        assertEquals(1, selectResp.getRows().size());

        Map<String, Object> row = selectResp.getRows().get(0);
        assertEquals("Alice", row.get("name"));
        assertNotNull(row.get("birth_date"));
        assertTrue(row.get("salary") instanceof Number);
        assertTrue(row.get("active") instanceof Boolean);
        assertNotNull(row.get("created_at"));

        // Cleanup
        queryService.execute("DROP TABLE " + tableName);
    }
}

