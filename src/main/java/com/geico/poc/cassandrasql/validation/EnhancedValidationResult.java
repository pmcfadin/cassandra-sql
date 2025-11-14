package com.geico.poc.cassandrasql.validation;

import java.util.ArrayList;
import java.util.List;

/**
 * Enhanced validation result with categorized errors, warnings, and suggestions
 */
public class EnhancedValidationResult {
    
    private boolean valid = true;
    private final List<ValidationIssue> errors = new ArrayList<>();
    private final List<ValidationIssue> warnings = new ArrayList<>();
    private final List<String> suggestions = new ArrayList<>();
    private final List<String> info = new ArrayList<>();
    
    public boolean isValid() {
        return valid;
    }
    
    public void setValid(boolean valid) {
        this.valid = valid;
    }
    
    public boolean hasErrors() {
        return !errors.isEmpty();
    }
    
    public boolean hasWarnings() {
        return !warnings.isEmpty();
    }
    
    public List<ValidationIssue> getErrors() {
        return errors;
    }
    
    public List<ValidationIssue> getWarnings() {
        return warnings;
    }
    
    public List<String> getSuggestions() {
        return suggestions;
    }
    
    public List<String> getInfo() {
        return info;
    }
    
    public void addError(String category, String message) {
        errors.add(new ValidationIssue(category, message));
        valid = false;
    }
    
    public void addWarning(String category, String message) {
        warnings.add(new ValidationIssue(category, message));
    }
    
    public void addSuggestion(String suggestion) {
        suggestions.add(suggestion);
    }
    
    public void addInfo(String info) {
        this.info.add(info);
    }
    
    /**
     * Get formatted error message
     */
    public String getErrorMessage() {
        if (errors.isEmpty()) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder();
        for (ValidationIssue error : errors) {
            sb.append("❌ ").append(error.getCategory()).append(": ")
              .append(error.getMessage()).append("\n");
        }
        
        // Add suggestions
        if (!suggestions.isEmpty()) {
            sb.append("\n");
            for (String suggestion : suggestions) {
                sb.append(suggestion).append("\n");
            }
        }
        
        return sb.toString().trim();
    }
    
    /**
     * Get formatted warning message
     */
    public String getWarningMessage() {
        if (warnings.isEmpty()) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder();
        for (ValidationIssue warning : warnings) {
            sb.append("⚠️  ").append(warning.getCategory()).append(": ")
              .append(warning.getMessage()).append("\n");
        }
        
        return sb.toString().trim();
    }
    
    /**
     * Get formatted info message
     */
    public String getInfoMessage() {
        if (info.isEmpty()) {
            return "";
        }
        
        return String.join("\n", info);
    }
    
    /**
     * Get full formatted output
     */
    public String getFormattedOutput() {
        StringBuilder sb = new StringBuilder();
        
        if (hasErrors()) {
            sb.append(getErrorMessage()).append("\n");
        }
        
        if (hasWarnings()) {
            sb.append("\n").append(getWarningMessage()).append("\n");
        }
        
        if (!info.isEmpty()) {
            sb.append("\n").append(getInfoMessage()).append("\n");
        }
        
        return sb.toString().trim();
    }
    
    @Override
    public String toString() {
        return getFormattedOutput();
    }
    
    /**
     * Validation issue (error or warning)
     */
    public static class ValidationIssue {
        private final String category;
        private final String message;
        
        public ValidationIssue(String category, String message) {
            this.category = category;
            this.message = message;
        }
        
        public String getCategory() {
            return category;
        }
        
        public String getMessage() {
            return message;
        }
        
        @Override
        public String toString() {
            return category + ": " + message;
        }
    }
}





