package com.geico.poc.cassandrasql;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class to split SQL into individual statements, handling:
 * - Multiple statements separated by semicolons
 * - DO blocks (PostgreSQL procedural blocks)
 * - String literals with semicolons
 * - Comments
 */
public class StatementSplitter {
    
    /**
     * Split SQL into individual statements.
     * Handles DO blocks, string literals, and comments.
     */
    public static List<String> split(String sql) {
        List<String> statements = new ArrayList<>();
        
        // Trim and check if empty
        sql = sql.trim();
        if (sql.isEmpty()) {
            return statements;
        }
        
        // Check for DO block (PostgreSQL procedural block)
        if (sql.toUpperCase().startsWith("DO")) {
            // DO blocks are single statements, even if they contain semicolons
            statements.add(sql);
            return statements;
        }
        
        // Split by semicolons, but respect string literals and DO blocks
        StringBuilder current = new StringBuilder();
        boolean inString = false;
        boolean inDollarQuote = false;
        char stringChar = 0;
        int doBlockDepth = 0;
        
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char next = (i + 1 < sql.length()) ? sql.charAt(i + 1) : 0;
            
            // Check for DO block start
            if (!inString && !inDollarQuote && doBlockDepth == 0) {
                if (i + 2 < sql.length() && 
                    (c == 'D' || c == 'd') && 
                    (next == 'O' || next == 'o') && 
                    Character.isWhitespace(sql.charAt(i + 2))) {
                    doBlockDepth = 1;
                }
            }
            
            // Check for $$ (dollar-quoted strings in PostgreSQL)
            if (c == '$' && next == '$') {
                inDollarQuote = !inDollarQuote;
                current.append(c);
                i++; // Skip next $
                current.append('$');
                continue;
            }
            
            // Handle string literals
            if (!inDollarQuote) {
                if (c == '\'' || c == '"') {
                    if (!inString) {
                        inString = true;
                        stringChar = c;
                    } else if (c == stringChar) {
                        // Check for escaped quote
                        if (next == c) {
                            current.append(c);
                            i++; // Skip next quote
                            current.append(c);
                            continue;
                        } else {
                            inString = false;
                        }
                    }
                }
            }
            
            // Handle semicolons
            if (c == ';' && !inString && !inDollarQuote) {
                if (doBlockDepth > 0) {
                    // Inside DO block, semicolons are part of the block
                    current.append(c);
                } else {
                    // End of statement
                    String stmt = current.toString().trim();
                    if (!stmt.isEmpty()) {
                        statements.add(stmt);
                    }
                    current = new StringBuilder();
                }
            } else {
                current.append(c);
            }
            
            // Check for DO block end (END $$; or END;)
            if (doBlockDepth > 0 && !inString && !inDollarQuote) {
                String remaining = sql.substring(i);
                if (remaining.toUpperCase().startsWith("END")) {
                    // Look for END $$ or END;
                    int endIdx = i + 3;
                    while (endIdx < sql.length() && Character.isWhitespace(sql.charAt(endIdx))) {
                        endIdx++;
                    }
                    if (endIdx < sql.length() - 1 && 
                        sql.charAt(endIdx) == '$' && sql.charAt(endIdx + 1) == '$') {
                        // Found END $$
                        doBlockDepth = 0;
                    } else if (endIdx < sql.length() && sql.charAt(endIdx) == ';') {
                        // Found END;
                        doBlockDepth = 0;
                    }
                }
            }
        }
        
        // Add final statement
        String stmt = current.toString().trim();
        if (!stmt.isEmpty()) {
            statements.add(stmt);
        }
        
        return statements;
    }
    
    /**
     * Check if SQL contains multiple statements
     */
    public static boolean hasMultipleStatements(String sql) {
        return split(sql).size() > 1;
    }
    
    /**
     * Check if SQL is a DO block
     */
    public static boolean isDoBlock(String sql) {
        return sql.trim().toUpperCase().startsWith("DO");
    }
}

