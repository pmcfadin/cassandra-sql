package com.geico.poc.cassandrasql.transaction;

/**
 * Exception thrown when a transaction conflict is detected
 */
public class TransactionConflictException extends RuntimeException {
    
    public TransactionConflictException(String message) {
        super(message);
    }
    
    public TransactionConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}







