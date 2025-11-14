package com.geico.poc.cassandrasql.calcite;

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.sql.type.SqlTypeName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Type mapping between PostgreSQL types and Calcite types.
 * Handles the conversion for proper type checking and validation.
 */
public class KvTypeFactory {
    
    private static final Logger log = LoggerFactory.getLogger(KvTypeFactory.class);
    
    /**
     * Convert a PostgreSQL type string to a Calcite RelDataType
     */
    public static RelDataType toCalciteType(RelDataTypeFactory typeFactory, String pgType) {
        if (pgType == null) {
            return typeFactory.createSqlType(SqlTypeName.ANY);
        }
        
        String upperType = pgType.toUpperCase().trim();
        
        // Handle parameterized types (e.g., VARCHAR(255))
        if (upperType.contains("(")) {
            String baseType = upperType.substring(0, upperType.indexOf('(')).trim();
            String params = upperType.substring(upperType.indexOf('(') + 1, upperType.indexOf(')')).trim();
            
            switch (baseType) {
                case "VARCHAR":
                case "CHAR":
                    try {
                        int length = Integer.parseInt(params);
                        return typeFactory.createSqlType(SqlTypeName.VARCHAR, length);
                    } catch (NumberFormatException e) {
                        return typeFactory.createSqlType(SqlTypeName.VARCHAR);
                    }
                case "DECIMAL":
                case "NUMERIC":
                    // Parse precision and scale
                    String[] parts = params.split(",");
                    try {
                        int precision = Integer.parseInt(parts[0].trim());
                        int scale = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 0;
                        return typeFactory.createSqlType(SqlTypeName.DECIMAL, precision, scale);
                    } catch (NumberFormatException e) {
                        return typeFactory.createSqlType(SqlTypeName.DECIMAL);
                    }
                default:
                    log.warn("Unknown parameterized type: {}, using base type", upperType);
                    upperType = baseType;
            }
        }
        
        // Map base types
        switch (upperType) {
            // Integer types
            case "INT":
            case "INTEGER":
            case "INT4":
                return typeFactory.createSqlType(SqlTypeName.INTEGER);
                
            case "SMALLINT":
            case "INT2":
                return typeFactory.createSqlType(SqlTypeName.SMALLINT);
                
            case "BIGINT":
            case "INT8":
                return typeFactory.createSqlType(SqlTypeName.BIGINT);
                
            // Floating point types
            case "REAL":
            case "FLOAT4":
                return typeFactory.createSqlType(SqlTypeName.REAL);
                
            case "DOUBLE":
            case "DOUBLE PRECISION":
            case "FLOAT8":
            case "FLOAT":
                return typeFactory.createSqlType(SqlTypeName.DOUBLE);
                
            case "DECIMAL":
            case "NUMERIC":
                return typeFactory.createSqlType(SqlTypeName.DECIMAL);
                
            // String types
            case "TEXT":
            case "VARCHAR":
            case "CHARACTER VARYING":
                return typeFactory.createSqlType(SqlTypeName.VARCHAR);
                
            case "CHAR":
            case "CHARACTER":
                return typeFactory.createSqlType(SqlTypeName.CHAR);
                
            // Boolean type
            case "BOOLEAN":
            case "BOOL":
                return typeFactory.createSqlType(SqlTypeName.BOOLEAN);
                
            // Date/time types
            case "DATE":
                return typeFactory.createSqlType(SqlTypeName.DATE);
                
            case "TIME":
                return typeFactory.createSqlType(SqlTypeName.TIME);
                
            case "TIMESTAMP":
            case "TIMESTAMP WITHOUT TIME ZONE":
                return typeFactory.createSqlType(SqlTypeName.TIMESTAMP);
                
            case "TIMESTAMPTZ":
            case "TIMESTAMP WITH TIME ZONE":
                return typeFactory.createSqlType(SqlTypeName.TIMESTAMP_WITH_LOCAL_TIME_ZONE);
                
            // Binary types
            case "BYTEA":
            case "BINARY":
            case "VARBINARY":
                return typeFactory.createSqlType(SqlTypeName.VARBINARY);
                
            // JSON types
            case "JSON":
            case "JSONB":
                // Calcite doesn't have a native JSON type, use VARCHAR for now
                // We'll handle JSON operations specially
                return typeFactory.createSqlType(SqlTypeName.VARCHAR);
                
            // Array types
            case "ARRAY":
                return typeFactory.createSqlType(SqlTypeName.ARRAY);
                
            // UUID type
            case "UUID":
                return typeFactory.createSqlType(SqlTypeName.CHAR, 36);
                
            // Interval type
            case "INTERVAL":
                return typeFactory.createSqlType(SqlTypeName.INTERVAL_DAY);
                
            default:
                log.warn("Unknown PostgreSQL type: {}, defaulting to VARCHAR", pgType);
                return typeFactory.createSqlType(SqlTypeName.VARCHAR);
        }
    }
    
    /**
     * Convert a Java class to a Calcite type
     */
    public static RelDataType fromJavaType(RelDataTypeFactory typeFactory, Class<?> javaType) {
        if (javaType == null) {
            return typeFactory.createSqlType(SqlTypeName.ANY);
        }
        
        if (javaType == Integer.class || javaType == int.class) {
            return typeFactory.createSqlType(SqlTypeName.INTEGER);
        } else if (javaType == Long.class || javaType == long.class) {
            return typeFactory.createSqlType(SqlTypeName.BIGINT);
        } else if (javaType == Short.class || javaType == short.class) {
            return typeFactory.createSqlType(SqlTypeName.SMALLINT);
        } else if (javaType == Double.class || javaType == double.class) {
            return typeFactory.createSqlType(SqlTypeName.DOUBLE);
        } else if (javaType == Float.class || javaType == float.class) {
            return typeFactory.createSqlType(SqlTypeName.REAL);
        } else if (javaType == Boolean.class || javaType == boolean.class) {
            return typeFactory.createSqlType(SqlTypeName.BOOLEAN);
        } else if (javaType == String.class) {
            return typeFactory.createSqlType(SqlTypeName.VARCHAR);
        } else if (javaType == java.sql.Date.class) {
            return typeFactory.createSqlType(SqlTypeName.DATE);
        } else if (javaType == java.sql.Time.class) {
            return typeFactory.createSqlType(SqlTypeName.TIME);
        } else if (javaType == java.sql.Timestamp.class) {
            return typeFactory.createSqlType(SqlTypeName.TIMESTAMP);
        } else if (javaType == byte[].class) {
            return typeFactory.createSqlType(SqlTypeName.VARBINARY);
        } else {
            log.warn("Unknown Java type: {}, defaulting to ANY", javaType.getName());
            return typeFactory.createSqlType(SqlTypeName.ANY);
        }
    }
}


