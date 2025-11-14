package com.geico.poc.cassandrasql.kv;

import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.HashMap;
import java.util.Map;

/**
 * Date and time functions for SQL queries in KV mode.
 * Implements PostgreSQL-compatible date/time functions.
 */
public class DateTimeFunctions {

    /**
     * Get current timestamp
     */
    public static Timestamp now() {
        return new Timestamp(System.currentTimeMillis());
    }

    /**
     * Get current date
     */
    public static Date currentDate() {
        return new Date(System.currentTimeMillis());
    }

    /**
     * Get current time
     */
    public static Time currentTime() {
        return new Time(System.currentTimeMillis());
    }

    /**
     * Extract a field from a timestamp
     * Supported fields: YEAR, MONTH, DAY, HOUR, MINUTE, SECOND, DOW (day of week), DOY (day of year), EPOCH
     */
    public static Object extract(String field, Object datetime) {
        if (datetime == null) {
            return null;
        }

        LocalDateTime ldt;
        if (datetime instanceof Timestamp) {
            ldt = ((Timestamp) datetime).toLocalDateTime();
        } else if (datetime instanceof Date) {
            ldt = ((Date) datetime).toLocalDate().atStartOfDay();
        } else if (datetime instanceof Time) {
            ldt = LocalDate.now().atTime(((Time) datetime).toLocalTime());
        } else {
            throw new IllegalArgumentException("Invalid datetime type: " + datetime.getClass());
        }

        String fieldUpper = field.toUpperCase();
        switch (fieldUpper) {
            case "YEAR":
                return ldt.getYear();
            case "MONTH":
                return ldt.getMonthValue();
            case "DAY":
                return ldt.getDayOfMonth();
            case "HOUR":
                return ldt.getHour();
            case "MINUTE":
                return ldt.getMinute();
            case "SECOND":
                return ldt.getSecond();
            case "DOW":  // Day of week (1-7, Sunday=1)
                return ldt.getDayOfWeek().getValue() % 7 + 1;
            case "DOY":  // Day of year (1-366)
                return ldt.getDayOfYear();
            case "EPOCH":  // Seconds since 1970-01-01
                if (datetime instanceof Timestamp) {
                    return ((Timestamp) datetime).getTime() / 1000;
                } else if (datetime instanceof Date) {
                    return ((Date) datetime).getTime() / 1000;
                }
                return 0;
            default:
                throw new IllegalArgumentException("Unknown field: " + field);
        }
    }

    /**
     * Truncate timestamp to specified precision
     * Supported: YEAR, MONTH, DAY, HOUR, MINUTE, SECOND
     */
    public static Timestamp dateTrunc(String precision, Timestamp timestamp) {
        if (timestamp == null) {
            return null;
        }

        LocalDateTime ldt = timestamp.toLocalDateTime();
        String precisionUpper = precision.toUpperCase();

        switch (precisionUpper) {
            case "YEAR":
                ldt = ldt.withMonth(1).withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS);
                break;
            case "MONTH":
                ldt = ldt.withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS);
                break;
            case "DAY":
                ldt = ldt.truncatedTo(ChronoUnit.DAYS);
                break;
            case "HOUR":
                ldt = ldt.truncatedTo(ChronoUnit.HOURS);
                break;
            case "MINUTE":
                ldt = ldt.truncatedTo(ChronoUnit.MINUTES);
                break;
            case "SECOND":
                ldt = ldt.truncatedTo(ChronoUnit.SECONDS);
                break;
            default:
                throw new IllegalArgumentException("Unknown precision: " + precision);
        }

        return Timestamp.valueOf(ldt);
    }

    /**
     * Add interval to timestamp
     * Interval format: "N UNIT" where UNIT is YEAR, MONTH, DAY, HOUR, MINUTE, SECOND
     */
    public static Timestamp timestampAdd(Timestamp timestamp, String interval) {
        if (timestamp == null || interval == null) {
            return timestamp;
        }

        String[] parts = interval.trim().split("\\s+", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid interval format: " + interval);
        }

        long amount = Long.parseLong(parts[0]);
        String unit = parts[1].toUpperCase();

        LocalDateTime ldt = timestamp.toLocalDateTime();

        switch (unit) {
            case "YEAR":
            case "YEARS":
                ldt = ldt.plusYears(amount);
                break;
            case "MONTH":
            case "MONTHS":
                ldt = ldt.plusMonths(amount);
                break;
            case "DAY":
            case "DAYS":
                ldt = ldt.plusDays(amount);
                break;
            case "HOUR":
            case "HOURS":
                ldt = ldt.plusHours(amount);
                break;
            case "MINUTE":
            case "MINUTES":
                ldt = ldt.plusMinutes(amount);
                break;
            case "SECOND":
            case "SECONDS":
                ldt = ldt.plusSeconds(amount);
                break;
            default:
                throw new IllegalArgumentException("Unknown unit: " + unit);
        }

        return Timestamp.valueOf(ldt);
    }

    /**
     * Add interval to date
     */
    public static Date dateAdd(Date date, String interval) {
        if (date == null || interval == null) {
            return date;
        }

        Timestamp ts = new Timestamp(date.getTime());
        Timestamp result = timestampAdd(ts, interval);
        return new Date(result.getTime());
    }

    /**
     * Subtract two timestamps to get interval in days
     */
    public static long timestampDiff(Timestamp ts1, Timestamp ts2) {
        if (ts1 == null || ts2 == null) {
            return 0;
        }
        return (ts1.getTime() - ts2.getTime()) / (1000 * 60 * 60 * 24);
    }

    /**
     * Format timestamp as string
     */
    public static String formatTimestamp(Timestamp timestamp, String format) {
        if (timestamp == null) {
            return null;
        }

        LocalDateTime ldt = timestamp.toLocalDateTime();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(format);
        return ldt.format(formatter);
    }

    /**
     * Parse string to timestamp
     */
    public static Timestamp parseTimestamp(String str) {
        if (str == null || str.trim().isEmpty()) {
            return null;
        }

        try {
            // Try ISO format first
            return Timestamp.valueOf(str);
        } catch (Exception e) {
            // Try parsing as date
            try {
                LocalDate date = LocalDate.parse(str);
                return Timestamp.valueOf(date.atStartOfDay());
            } catch (Exception e2) {
                throw new IllegalArgumentException("Invalid timestamp format: " + str);
            }
        }
    }

    /**
     * Parse string to date
     */
    public static Date parseDate(String str) {
        if (str == null || str.trim().isEmpty()) {
            return null;
        }

        try {
            LocalDate date = LocalDate.parse(str);
            return Date.valueOf(date);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid date format: " + str);
        }
    }

    /**
     * Parse string to time
     */
    public static Time parseTime(String str) {
        if (str == null || str.trim().isEmpty()) {
            return null;
        }

        try {
            LocalTime time = LocalTime.parse(str);
            return Time.valueOf(time);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid time format: " + str);
        }
    }

    /**
     * Get age (interval) between two timestamps
     * Returns a map with years, months, days
     */
    public static Map<String, Long> age(Timestamp ts1, Timestamp ts2) {
        if (ts1 == null || ts2 == null) {
            return new HashMap<>();
        }

        LocalDateTime ldt1 = ts1.toLocalDateTime();
        LocalDateTime ldt2 = ts2.toLocalDateTime();

        Period period = Period.between(ldt2.toLocalDate(), ldt1.toLocalDate());
        
        Map<String, Long> result = new HashMap<>();
        result.put("years", (long) period.getYears());
        result.put("months", (long) period.getMonths());
        result.put("days", (long) period.getDays());
        
        return result;
    }

    /**
     * Check if year is a leap year
     */
    public static boolean isLeapYear(int year) {
        return Year.isLeap(year);
    }

    /**
     * Get the last day of the month for a given date
     */
    public static Date lastDayOfMonth(Date date) {
        if (date == null) {
            return null;
        }

        LocalDate ld = date.toLocalDate();
        LocalDate lastDay = ld.with(TemporalAdjusters.lastDayOfMonth());
        return Date.valueOf(lastDay);
    }

    /**
     * Get the first day of the month for a given date
     */
    public static Date firstDayOfMonth(Date date) {
        if (date == null) {
            return null;
        }

        LocalDate ld = date.toLocalDate();
        LocalDate firstDay = ld.with(TemporalAdjusters.firstDayOfMonth());
        return Date.valueOf(firstDay);
    }

    /**
     * Add days to a date
     */
    public static Date addDays(Date date, int days) {
        if (date == null) {
            return null;
        }

        LocalDate ld = date.toLocalDate().plusDays(days);
        return Date.valueOf(ld);
    }

    /**
     * Add months to a date
     */
    public static Date addMonths(Date date, int months) {
        if (date == null) {
            return null;
        }

        LocalDate ld = date.toLocalDate().plusMonths(months);
        return Date.valueOf(ld);
    }

    /**
     * Add years to a date
     */
    public static Date addYears(Date date, int years) {
        if (date == null) {
            return null;
        }

        LocalDate ld = date.toLocalDate().plusYears(years);
        return Date.valueOf(ld);
    }
}

