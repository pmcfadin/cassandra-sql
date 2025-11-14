package com.geico.poc.cassandrasql.kv;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Helper utilities for JSON/JSONB operations in KV mode.
 * 
 * Supports PostgreSQL-compatible JSON operators:
 * - -> : Extract JSON object field or array element (returns JSON)
 * - ->> : Extract JSON object field or array element as text
 * - #> : Extract JSON at path (returns JSON)
 * - #>> : Extract JSON at path as text
 * 
 * And JSON functions:
 * - jsonb_extract_path(json, path...) : Extract value at path
 * - jsonb_extract_path_text(json, path...) : Extract value at path as text
 * - jsonb_typeof(json) : Get type of JSON value
 * - jsonb_array_length(json) : Get array length
 */
public class JsonHelper {
    
    private static final Logger log = LoggerFactory.getLogger(JsonHelper.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * Validate that a string is valid JSON
     */
    public static boolean isValidJson(String json) {
        if (json == null) {
            return false;
        }
        try {
            objectMapper.readTree(json);
            return true;
        } catch (JsonProcessingException e) {
            return false;
        }
    }
    
    /**
     * Validate and throw exception if invalid
     */
    public static void validateJson(String json) throws IllegalArgumentException {
        if (json == null) {
            return; // NULL is valid
        }
        try {
            objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid JSON: " + e.getMessage(), e);
        }
    }
    
    /**
     * Extract JSON field or array element (-> operator)
     * Returns JSON string or null if not found
     */
    public static String extractJson(String json, String keyOrIndex) {
        if (json == null) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            JsonNode result = extractNode(node, keyOrIndex);
            return result != null ? result.toString() : null;
        } catch (JsonProcessingException e) {
            log.debug("Failed to parse JSON: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Extract JSON field or array element as text (->> operator)
     * Returns text value or null if not found
     */
    public static String extractText(String json, String keyOrIndex) {
        if (json == null) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            JsonNode result = extractNode(node, keyOrIndex);
            if (result == null) {
                return null;
            }
            // For text nodes, return unquoted value
            if (result.isTextual()) {
                return result.asText();
            }
            // For other types, return JSON representation
            return result.toString();
        } catch (JsonProcessingException e) {
            log.debug("Failed to parse JSON: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Extract JSON at path (#> operator)
     * Returns JSON string or null if not found
     */
    public static String extractJsonPath(String json, List<String> path) {
        if (json == null || path == null || path.isEmpty()) {
            return json;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            for (String key : path) {
                node = extractNode(node, key);
                if (node == null) {
                    return null;
                }
            }
            return node.toString();
        } catch (JsonProcessingException e) {
            log.debug("Failed to parse JSON: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Extract JSON at path as text (#>> operator)
     * Returns text value or null if not found
     */
    public static String extractTextPath(String json, List<String> path) {
        if (json == null || path == null || path.isEmpty()) {
            return json;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            for (String key : path) {
                node = extractNode(node, key);
                if (node == null) {
                    return null;
                }
            }
            // For text nodes, return unquoted value
            if (node.isTextual()) {
                return node.asText();
            }
            // For other types, return JSON representation
            return node.toString();
        } catch (JsonProcessingException e) {
            log.debug("Failed to parse JSON: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Extract a node by key (for objects) or index (for arrays)
     */
    private static JsonNode extractNode(JsonNode node, String keyOrIndex) {
        if (node == null) {
            return null;
        }
        
        // Try as object key first
        if (node.isObject()) {
            return node.get(keyOrIndex);
        }
        
        // Try as array index
        if (node.isArray()) {
            try {
                int index = Integer.parseInt(keyOrIndex);
                return node.get(index);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        
        return null;
    }
    
    /**
     * Get the type of a JSON value
     * Returns: "object", "array", "string", "number", "boolean", "null"
     */
    public static String jsonbTypeof(String json) {
        if (json == null) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node.isObject()) return "object";
            if (node.isArray()) return "array";
            if (node.isTextual()) return "string";
            if (node.isNumber()) return "number";
            if (node.isBoolean()) return "boolean";
            if (node.isNull()) return "null";
            return "unknown";
        } catch (JsonProcessingException e) {
            return null;
        }
    }
    
    /**
     * Get the length of a JSON array
     * Returns -1 if not an array or invalid JSON
     */
    public static int jsonbArrayLength(String json) {
        if (json == null) {
            return -1;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node.isArray()) {
                return node.size();
            }
            return -1;
        } catch (JsonProcessingException e) {
            return -1;
        }
    }
    
    /**
     * Parse a PostgreSQL array literal like '{a,b,c}' into a list
     */
    public static List<String> parseArrayLiteral(String arrayLiteral) {
        List<String> result = new ArrayList<>();
        if (arrayLiteral == null || !arrayLiteral.startsWith("{") || !arrayLiteral.endsWith("}")) {
            return result;
        }
        
        String content = arrayLiteral.substring(1, arrayLiteral.length() - 1);
        if (content.isEmpty()) {
            return result;
        }
        
        // Simple comma split (doesn't handle nested arrays or quoted commas)
        for (String item : content.split(",")) {
            result.add(item.trim());
        }
        
        return result;
    }
    
    /**
     * Check if JSON contains a specific key at the root level
     */
    public static boolean containsKey(String json, String key) {
        if (json == null || key == null) {
            return false;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            return node.isObject() && node.has(key);
        } catch (JsonProcessingException e) {
            return false;
        }
    }
    
    /**
     * Pretty-print JSON
     */
    public static String prettyPrint(String json) {
        if (json == null) {
            return null;
        }
        try {
            Object obj = objectMapper.readValue(json, Object.class);
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return json;
        }
    }
}


