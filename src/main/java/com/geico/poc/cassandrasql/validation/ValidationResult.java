package com.geico.poc.cassandrasql.validation;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of SQL validation
 */
public class ValidationResult {
    private final List<String> errors = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    
    public void addError(String error) {
        errors.add(error);
    }
    
    public void addWarning(String warning) {
        warnings.add(warning);
    }
    
    public boolean hasErrors() {
        return !errors.isEmpty();
    }
    
    public boolean hasWarnings() {
        return !warnings.isEmpty();
    }
    
    public List<String> getErrors() {
        return errors;
    }
    
    public List<String> getWarnings() {
        return warnings;
    }
    
    public boolean isValid() {
        return !hasErrors();
    }
    
    public String getErrorMessage() {
        if (!hasErrors()) {
            return null;
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("SQL validation failed:\n");
        for (String error : errors) {
            sb.append("  ❌ ").append(error).append("\n");
        }
        
        if (hasWarnings()) {
            sb.append("\nWarnings:\n");
            for (String warning : warnings) {
                sb.append("  ⚠️  ").append(warning).append("\n");
            }
        }
        
        return sb.toString();
    }
    
    public String getWarningMessage() {
        if (!hasWarnings()) {
            return null;
        }
        
        StringBuilder sb = new StringBuilder();
        for (String warning : warnings) {
            sb.append("⚠️  ").append(warning).append("\n");
        }
        return sb.toString();
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        
        if (hasErrors()) {
            sb.append("Errors: ").append(errors).append("\n");
        }
        
        if (hasWarnings()) {
            sb.append("Warnings: ").append(warnings);
        }
        
        if (!hasErrors() && !hasWarnings()) {
            sb.append("Valid");
        }
        
        return sb.toString();
    }
}







