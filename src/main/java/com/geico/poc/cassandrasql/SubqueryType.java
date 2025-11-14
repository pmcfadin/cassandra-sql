package com.geico.poc.cassandrasql;

/**
 * Types of subqueries
 */
public enum SubqueryType {
    IN,          // WHERE col IN (SELECT ...)
    EXISTS,      // WHERE EXISTS (SELECT ...)
    NOT_EXISTS,  // WHERE NOT EXISTS (SELECT ...)
    SCALAR,      // SELECT (SELECT ...) or WHERE col = (SELECT ...)
    FROM         // FROM (SELECT ...) AS alias
}





