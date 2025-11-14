package com.geico.poc.cassandrasql.dto;

public class QueryRequest {
    private String sql;

    public QueryRequest() {
    }

    public QueryRequest(String sql) {
        this.sql = sql;
    }

    public String getSql() {
        return sql;
    }

    public void setSql(String sql) {
        this.sql = sql;
    }
}







