package com.geico.poc.cassandrasql.kv;

import org.junit.jupiter.api.*;

import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DateTimeFunctions utility class
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class DateTimeFunctionsTest {

    @Test
    @Order(1)
    public void testNow() {
        Timestamp now = DateTimeFunctions.now();
        assertNotNull(now);
        assertTrue(now.getTime() > 0);
        
        // Should be close to current time (within 1 second)
        long diff = Math.abs(System.currentTimeMillis() - now.getTime());
        assertTrue(diff < 1000, "NOW() should return current timestamp");
    }

    @Test
    @Order(2)
    public void testCurrentDate() {
        Date currentDate = DateTimeFunctions.currentDate();
        assertNotNull(currentDate);
        
        // Should match today's date
        LocalDate today = LocalDate.now();
        LocalDate resultDate = currentDate.toLocalDate();
        assertEquals(today, resultDate);
    }

    @Test
    @Order(3)
    public void testCurrentTime() {
        Time currentTime = DateTimeFunctions.currentTime();
        assertNotNull(currentTime);
        assertTrue(currentTime.getTime() > 0);
    }

    @Test
    @Order(4)
    public void testExtractYear() {
        Timestamp ts = Timestamp.valueOf("2024-06-15 14:30:45");
        Object year = DateTimeFunctions.extract("YEAR", ts);
        assertEquals(2024, year);
    }

    @Test
    @Order(5)
    public void testExtractMonth() {
        Timestamp ts = Timestamp.valueOf("2024-06-15 14:30:45");
        Object month = DateTimeFunctions.extract("MONTH", ts);
        assertEquals(6, month);
    }

    @Test
    @Order(6)
    public void testExtractDay() {
        Timestamp ts = Timestamp.valueOf("2024-06-15 14:30:45");
        Object day = DateTimeFunctions.extract("DAY", ts);
        assertEquals(15, day);
    }

    @Test
    @Order(7)
    public void testExtractHour() {
        Timestamp ts = Timestamp.valueOf("2024-06-15 14:30:45");
        Object hour = DateTimeFunctions.extract("HOUR", ts);
        assertEquals(14, hour);
    }

    @Test
    @Order(8)
    public void testExtractMinute() {
        Timestamp ts = Timestamp.valueOf("2024-06-15 14:30:45");
        Object minute = DateTimeFunctions.extract("MINUTE", ts);
        assertEquals(30, minute);
    }

    @Test
    @Order(9)
    public void testExtractSecond() {
        Timestamp ts = Timestamp.valueOf("2024-06-15 14:30:45");
        Object second = DateTimeFunctions.extract("SECOND", ts);
        assertEquals(45, second);
    }

    @Test
    @Order(10)
    public void testExtractDayOfYear() {
        Timestamp ts = Timestamp.valueOf("2024-01-15 12:00:00");
        Object doy = DateTimeFunctions.extract("DOY", ts);
        assertEquals(15, doy); // 15th day of the year
    }

    @Test
    @Order(11)
    public void testExtractEpoch() {
        Timestamp ts = Timestamp.valueOf("1970-01-01 00:00:00");
        Object epoch = DateTimeFunctions.extract("EPOCH", ts);
        // Should be close to 0 (accounting for timezone differences)
        assertTrue(Math.abs((Long) epoch) < 86400); // Within 24 hours of epoch
    }

    @Test
    @Order(12)
    public void testDateTruncYear() {
        Timestamp ts = Timestamp.valueOf("2024-06-15 14:30:45");
        Timestamp truncated = DateTimeFunctions.dateTrunc("YEAR", ts);
        
        assertEquals("2024-01-01 00:00:00.0", truncated.toString());
    }

    @Test
    @Order(13)
    public void testDateTruncMonth() {
        Timestamp ts = Timestamp.valueOf("2024-06-15 14:30:45");
        Timestamp truncated = DateTimeFunctions.dateTrunc("MONTH", ts);
        
        assertEquals("2024-06-01 00:00:00.0", truncated.toString());
    }

    @Test
    @Order(14)
    public void testDateTruncDay() {
        Timestamp ts = Timestamp.valueOf("2024-06-15 14:30:45");
        Timestamp truncated = DateTimeFunctions.dateTrunc("DAY", ts);
        
        assertEquals("2024-06-15 00:00:00.0", truncated.toString());
    }

    @Test
    @Order(15)
    public void testDateTruncHour() {
        Timestamp ts = Timestamp.valueOf("2024-06-15 14:30:45");
        Timestamp truncated = DateTimeFunctions.dateTrunc("HOUR", ts);
        
        assertEquals("2024-06-15 14:00:00.0", truncated.toString());
    }

    @Test
    @Order(16)
    public void testTimestampAddDays() {
        Timestamp ts = Timestamp.valueOf("2024-01-15 12:00:00");
        Timestamp result = DateTimeFunctions.timestampAdd(ts, "10 DAYS");
        
        assertEquals("2024-01-25 12:00:00.0", result.toString());
    }

    @Test
    @Order(17)
    public void testTimestampAddMonths() {
        Timestamp ts = Timestamp.valueOf("2024-01-15 12:00:00");
        Timestamp result = DateTimeFunctions.timestampAdd(ts, "3 MONTHS");
        
        assertEquals("2024-04-15 12:00:00.0", result.toString());
    }

    @Test
    @Order(18)
    public void testTimestampAddYears() {
        Timestamp ts = Timestamp.valueOf("2024-01-15 12:00:00");
        Timestamp result = DateTimeFunctions.timestampAdd(ts, "2 YEARS");
        
        assertEquals("2026-01-15 12:00:00.0", result.toString());
    }

    @Test
    @Order(19)
    public void testTimestampAddHours() {
        Timestamp ts = Timestamp.valueOf("2024-01-15 12:00:00");
        Timestamp result = DateTimeFunctions.timestampAdd(ts, "5 HOURS");
        
        assertEquals("2024-01-15 17:00:00.0", result.toString());
    }

    @Test
    @Order(20)
    public void testDateAdd() {
        Date date = Date.valueOf("2024-01-15");
        Date result = DateTimeFunctions.dateAdd(date, "7 DAYS");
        
        assertEquals("2024-01-22", result.toString());
    }

    @Test
    @Order(21)
    public void testTimestampDiff() {
        Timestamp ts1 = Timestamp.valueOf("2024-01-25 12:00:00");
        Timestamp ts2 = Timestamp.valueOf("2024-01-15 12:00:00");
        
        long diff = DateTimeFunctions.timestampDiff(ts1, ts2);
        assertEquals(10, diff); // 10 days difference
    }

    @Test
    @Order(22)
    public void testParseTimestamp() {
        Timestamp ts = DateTimeFunctions.parseTimestamp("2024-06-15 14:30:45");
        assertNotNull(ts);
        assertEquals("2024-06-15 14:30:45.0", ts.toString());
    }

    @Test
    @Order(23)
    public void testParseDate() {
        Date date = DateTimeFunctions.parseDate("2024-06-15");
        assertNotNull(date);
        assertEquals("2024-06-15", date.toString());
    }

    @Test
    @Order(24)
    public void testParseTime() {
        Time time = DateTimeFunctions.parseTime("14:30:45");
        assertNotNull(time);
        assertEquals("14:30:45", time.toString());
    }

    @Test
    @Order(25)
    public void testAge() {
        Timestamp ts1 = Timestamp.valueOf("2024-06-15 12:00:00");
        Timestamp ts2 = Timestamp.valueOf("2020-01-10 12:00:00");
        
        Map<String, Long> age = DateTimeFunctions.age(ts1, ts2);
        
        assertNotNull(age);
        assertEquals(4, age.get("years").longValue());
        assertEquals(5, age.get("months").longValue());
        assertEquals(5, age.get("days").longValue());
    }

    @Test
    @Order(26)
    public void testIsLeapYear() {
        assertTrue(DateTimeFunctions.isLeapYear(2024)); // 2024 is a leap year
        assertFalse(DateTimeFunctions.isLeapYear(2023)); // 2023 is not
        assertTrue(DateTimeFunctions.isLeapYear(2000)); // 2000 is a leap year
        assertFalse(DateTimeFunctions.isLeapYear(1900)); // 1900 is not (century rule)
    }

    @Test
    @Order(27)
    public void testLastDayOfMonth() {
        Date date = Date.valueOf("2024-02-15");
        Date lastDay = DateTimeFunctions.lastDayOfMonth(date);
        
        assertEquals("2024-02-29", lastDay.toString()); // 2024 is a leap year
    }

    @Test
    @Order(28)
    public void testFirstDayOfMonth() {
        Date date = Date.valueOf("2024-06-15");
        Date firstDay = DateTimeFunctions.firstDayOfMonth(date);
        
        assertEquals("2024-06-01", firstDay.toString());
    }

    @Test
    @Order(29)
    public void testAddDays() {
        Date date = Date.valueOf("2024-01-15");
        Date result = DateTimeFunctions.addDays(date, 10);
        
        assertEquals("2024-01-25", result.toString());
    }

    @Test
    @Order(30)
    public void testAddMonths() {
        Date date = Date.valueOf("2024-01-15");
        Date result = DateTimeFunctions.addMonths(date, 3);
        
        assertEquals("2024-04-15", result.toString());
    }

    @Test
    @Order(31)
    public void testAddYears() {
        Date date = Date.valueOf("2024-01-15");
        Date result = DateTimeFunctions.addYears(date, 2);
        
        assertEquals("2026-01-15", result.toString());
    }

    @Test
    @Order(32)
    public void testFormatTimestamp() {
        Timestamp ts = Timestamp.valueOf("2024-06-15 14:30:45");
        String formatted = DateTimeFunctions.formatTimestamp(ts, "yyyy-MM-dd");
        
        assertEquals("2024-06-15", formatted);
    }

    @Test
    @Order(33)
    public void testExtractWithNull() {
        Object result = DateTimeFunctions.extract("YEAR", null);
        assertNull(result);
    }

    @Test
    @Order(34)
    public void testDateTruncWithNull() {
        Timestamp result = DateTimeFunctions.dateTrunc("DAY", null);
        assertNull(result);
    }

    @Test
    @Order(35)
    public void testTimestampAddWithNull() {
        Timestamp result = DateTimeFunctions.timestampAdd(null, "5 DAYS");
        assertNull(result);
    }
}

