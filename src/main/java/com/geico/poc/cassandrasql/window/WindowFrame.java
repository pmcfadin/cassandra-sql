package com.geico.poc.cassandrasql.window;

/**
 * Window frame specification.
 * 
 * Represents: ROWS BETWEEN ... AND ... or RANGE BETWEEN ... AND ...
 */
public class WindowFrame {
    
    public enum FrameType {
        ROWS,   // Physical rows
        RANGE   // Logical range based on values
    }
    
    public enum BoundType {
        UNBOUNDED_PRECEDING,  // Start of partition
        PRECEDING,            // N rows/values before current
        CURRENT_ROW,          // Current row
        FOLLOWING,            // N rows/values after current
        UNBOUNDED_FOLLOWING   // End of partition
    }
    
    private final FrameType frameType;
    private final BoundType lowerBoundType;
    private final Integer lowerBoundOffset;
    private final BoundType upperBoundType;
    private final Integer upperBoundOffset;
    
    public WindowFrame(FrameType frameType,
                      BoundType lowerBoundType, Integer lowerBoundOffset,
                      BoundType upperBoundType, Integer upperBoundOffset) {
        this.frameType = frameType;
        this.lowerBoundType = lowerBoundType;
        this.lowerBoundOffset = lowerBoundOffset;
        this.upperBoundType = upperBoundType;
        this.upperBoundOffset = upperBoundOffset;
    }
    
    /**
     * Default frame: RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
     */
    public static WindowFrame defaultFrame() {
        return new WindowFrame(
            FrameType.RANGE,
            BoundType.UNBOUNDED_PRECEDING, null,
            BoundType.CURRENT_ROW, null
        );
    }
    
    public FrameType getFrameType() {
        return frameType;
    }
    
    public BoundType getLowerBoundType() {
        return lowerBoundType;
    }
    
    public Integer getLowerBoundOffset() {
        return lowerBoundOffset;
    }
    
    public BoundType getUpperBoundType() {
        return upperBoundType;
    }
    
    public Integer getUpperBoundOffset() {
        return upperBoundOffset;
    }
    
    @Override
    public String toString() {
        return String.format("%s BETWEEN %s AND %s",
                frameType,
                formatBound(lowerBoundType, lowerBoundOffset),
                formatBound(upperBoundType, upperBoundOffset));
    }
    
    private String formatBound(BoundType type, Integer offset) {
        switch (type) {
            case UNBOUNDED_PRECEDING:
                return "UNBOUNDED PRECEDING";
            case PRECEDING:
                return offset + " PRECEDING";
            case CURRENT_ROW:
                return "CURRENT ROW";
            case FOLLOWING:
                return offset + " FOLLOWING";
            case UNBOUNDED_FOLLOWING:
                return "UNBOUNDED FOLLOWING";
            default:
                return type.toString();
        }
    }
}



