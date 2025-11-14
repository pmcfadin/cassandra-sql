package com.geico.poc.cassandrasql.dto;

import java.util.List;
import java.util.Map;

public class QueryResponse {
    private List<Map<String, Object>> rows;
    private int rowCount;
    private String error;
    private List<String> columns;

    public QueryResponse() {
    }

    public QueryResponse(List<Map<String, Object>> rows, List<String> columns) {
        this.rows = rows;
        this.columns = columns;
        this.rowCount = rows != null ? rows.size() : 0;
    }

    public static QueryResponse error(String message) {
        QueryResponse response = new QueryResponse();
        response.error = message;
        response.rowCount = 0;
        return response;
    }

    // Getters and setters
    public List<Map<String, Object>> getRows() {
        return rows;
    }

    public void setRows(List<Map<String, Object>> rows) {
        this.rows = rows;
        this.rowCount = rows != null ? rows.size() : 0;
    }

    public int getRowCount() {
        return rowCount;
    }

    public void setRowCount(int rowCount) {
        this.rowCount = rowCount;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public List<String> getColumns() {
        return columns;
    }

    public void setColumns(List<String> columns) {
        this.columns = columns;
    }
}







