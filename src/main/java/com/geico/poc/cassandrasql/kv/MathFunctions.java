package com.geico.poc.cassandrasql.kv;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Mathematical functions for SQL queries.
 * Supports standard SQL math functions including trigonometric, logarithmic, and rounding operations.
 */
public class MathFunctions {
    
    private static final MathContext DEFAULT_CONTEXT = MathContext.DECIMAL128;
    
    // ========== Basic Math Functions ==========
    
    /**
     * Returns the absolute value of a number
     */
    public static Object abs(Object value) {
        if (value == null) return null;
        
        if (value instanceof BigDecimal) {
            return ((BigDecimal) value).abs();
        } else if (value instanceof Double) {
            return Math.abs((Double) value);
        } else if (value instanceof Float) {
            return Math.abs((Float) value);
        } else if (value instanceof Long) {
            return Math.abs((Long) value);
        } else if (value instanceof Integer) {
            return Math.abs((Integer) value);
        }
        
        return Math.abs(toDouble(value));
    }
    
    /**
     * Returns the smallest integer greater than or equal to the argument
     */
    public static Object ceil(Object value) {
        if (value == null) return null;
        
        if (value instanceof BigDecimal) {
            return ((BigDecimal) value).setScale(0, RoundingMode.CEILING);
        }
        
        return Math.ceil(toDouble(value));
    }
    
    /**
     * Returns the largest integer less than or equal to the argument
     */
    public static Object floor(Object value) {
        if (value == null) return null;
        
        if (value instanceof BigDecimal) {
            return ((BigDecimal) value).setScale(0, RoundingMode.FLOOR);
        }
        
        return Math.floor(toDouble(value));
    }
    
    /**
     * Rounds a number to the specified number of decimal places
     */
    public static Object round(Object value, int scale) {
        if (value == null) return null;
        
        if (value instanceof BigDecimal) {
            return ((BigDecimal) value).setScale(scale, RoundingMode.HALF_UP);
        }
        
        double d = toDouble(value);
        double multiplier = Math.pow(10, scale);
        return Math.round(d * multiplier) / multiplier;
    }
    
    /**
     * Rounds a number to 0 decimal places
     */
    public static Object round(Object value) {
        return round(value, 0);
    }
    
    /**
     * Truncates a number to the specified number of decimal places
     */
    public static Object trunc(Object value, int scale) {
        if (value == null) return null;
        
        if (value instanceof BigDecimal) {
            return ((BigDecimal) value).setScale(scale, RoundingMode.DOWN);
        }
        
        double d = toDouble(value);
        double multiplier = Math.pow(10, scale);
        // Truncate towards zero (not floor)
        if (d >= 0) {
            return Math.floor(d * multiplier) / multiplier;
        } else {
            return Math.ceil(d * multiplier) / multiplier;
        }
    }
    
    /**
     * Truncates a number to 0 decimal places
     */
    public static Object trunc(Object value) {
        return trunc(value, 0);
    }
    
    // ========== Trigonometric Functions ==========
    
    /**
     * Returns the sine of an angle (in radians)
     */
    public static Double sin(Object value) {
        if (value == null) return null;
        return Math.sin(toDouble(value));
    }
    
    /**
     * Returns the cosine of an angle (in radians)
     */
    public static Double cos(Object value) {
        if (value == null) return null;
        return Math.cos(toDouble(value));
    }
    
    /**
     * Returns the tangent of an angle (in radians)
     */
    public static Double tan(Object value) {
        if (value == null) return null;
        return Math.tan(toDouble(value));
    }
    
    /**
     * Returns the arc sine (inverse sine) in radians
     */
    public static Double asin(Object value) {
        if (value == null) return null;
        return Math.asin(toDouble(value));
    }
    
    /**
     * Returns the arc cosine (inverse cosine) in radians
     */
    public static Double acos(Object value) {
        if (value == null) return null;
        return Math.acos(toDouble(value));
    }
    
    /**
     * Returns the arc tangent (inverse tangent) in radians
     */
    public static Double atan(Object value) {
        if (value == null) return null;
        return Math.atan(toDouble(value));
    }
    
    /**
     * Returns the arc tangent of y/x in radians
     */
    public static Double atan2(Object y, Object x) {
        if (y == null || x == null) return null;
        return Math.atan2(toDouble(y), toDouble(x));
    }
    
    /**
     * Converts radians to degrees
     */
    public static Double degrees(Object value) {
        if (value == null) return null;
        return Math.toDegrees(toDouble(value));
    }
    
    /**
     * Converts degrees to radians
     */
    public static Double radians(Object value) {
        if (value == null) return null;
        return Math.toRadians(toDouble(value));
    }
    
    // ========== Exponential and Logarithmic Functions ==========
    
    /**
     * Returns e raised to the power of the argument
     */
    public static Double exp(Object value) {
        if (value == null) return null;
        return Math.exp(toDouble(value));
    }
    
    /**
     * Returns the natural logarithm (base e)
     */
    public static Double ln(Object value) {
        if (value == null) return null;
        double d = toDouble(value);
        if (d <= 0) {
            throw new IllegalArgumentException("Cannot take logarithm of non-positive number: " + d);
        }
        return Math.log(d);
    }
    
    /**
     * Returns the base-10 logarithm
     */
    public static Double log(Object value) {
        if (value == null) return null;
        double d = toDouble(value);
        if (d <= 0) {
            throw new IllegalArgumentException("Cannot take logarithm of non-positive number: " + d);
        }
        return Math.log10(d);
    }
    
    /**
     * Returns the logarithm with specified base
     */
    public static Double log(Object base, Object value) {
        if (base == null || value == null) return null;
        double b = toDouble(base);
        double v = toDouble(value);
        if (b <= 0 || b == 1) {
            throw new IllegalArgumentException("Invalid logarithm base: " + b);
        }
        if (v <= 0) {
            throw new IllegalArgumentException("Cannot take logarithm of non-positive number: " + v);
        }
        return Math.log(v) / Math.log(b);
    }
    
    /**
     * Returns base raised to the power of exponent
     */
    public static Object power(Object base, Object exponent) {
        if (base == null || exponent == null) return null;
        
        if (base instanceof BigDecimal && exponent instanceof Integer) {
            return ((BigDecimal) base).pow((Integer) exponent, DEFAULT_CONTEXT);
        }
        
        return Math.pow(toDouble(base), toDouble(exponent));
    }
    
    /**
     * Returns the square root
     */
    public static Double sqrt(Object value) {
        if (value == null) return null;
        double d = toDouble(value);
        if (d < 0) {
            throw new IllegalArgumentException("Cannot take square root of negative number: " + d);
        }
        return Math.sqrt(d);
    }
    
    /**
     * Returns the cube root
     */
    public static Double cbrt(Object value) {
        if (value == null) return null;
        return Math.cbrt(toDouble(value));
    }
    
    // ========== Other Math Functions ==========
    
    /**
     * Returns the remainder of division (modulo)
     */
    public static Object mod(Object dividend, Object divisor) {
        if (dividend == null || divisor == null) return null;
        
        if (dividend instanceof BigDecimal && divisor instanceof BigDecimal) {
            return ((BigDecimal) dividend).remainder((BigDecimal) divisor, DEFAULT_CONTEXT);
        }
        
        if (dividend instanceof Long && divisor instanceof Long) {
            return (Long) dividend % (Long) divisor;
        }
        
        if (dividend instanceof Integer && divisor instanceof Integer) {
            return (Integer) dividend % (Integer) divisor;
        }
        
        return toDouble(dividend) % toDouble(divisor);
    }
    
    /**
     * Returns the sign of a number (-1, 0, or 1)
     */
    public static Integer sign(Object value) {
        if (value == null) return null;
        
        if (value instanceof BigDecimal) {
            return ((BigDecimal) value).signum();
        }
        
        double d = toDouble(value);
        if (d > 0) return 1;
        if (d < 0) return -1;
        return 0;
    }
    
    /**
     * Returns a random number between 0 and 1
     */
    public static Double random() {
        return Math.random();
    }
    
    /**
     * Returns the value of PI
     */
    public static Double pi() {
        return Math.PI;
    }
    
    // ========== Helper Methods ==========
    
    /**
     * Convert any numeric type to double
     */
    private static double toDouble(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value instanceof String) {
            return Double.parseDouble((String) value);
        }
        throw new IllegalArgumentException("Cannot convert to number: " + value);
    }
    
    /**
     * Convert any numeric type to BigDecimal
     */
    public static BigDecimal toBigDecimal(Object value) {
        if (value == null) return null;
        
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        } else if (value instanceof Double) {
            return BigDecimal.valueOf((Double) value);
        } else if (value instanceof Float) {
            return BigDecimal.valueOf((Float) value);
        } else if (value instanceof Long) {
            return BigDecimal.valueOf((Long) value);
        } else if (value instanceof Integer) {
            return BigDecimal.valueOf((Integer) value);
        } else if (value instanceof String) {
            return new BigDecimal((String) value);
        }
        
        throw new IllegalArgumentException("Cannot convert to BigDecimal: " + value);
    }
}

