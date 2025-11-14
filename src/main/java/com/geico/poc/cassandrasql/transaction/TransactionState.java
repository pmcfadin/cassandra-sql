package com.geico.poc.cassandrasql.transaction;

/**
 * Transaction state enum
 */
public enum TransactionState {
    NONE,       // No active transaction
    ACTIVE,     // Transaction in progress
    COMMITTED,  // Transaction committed successfully
    ABORTED     // Transaction rolled back
}







