#!/bin/bash

# Key Decoder for Cassandra SQL KV Store
# Decodes hex keys from kv_store, kv_locks, and kv_writes tables
#
# Usage: ./decode-key.sh <hex_key>
# Example: ./decode-key.sh 0x0500000000000000030000000000000000726f7769645f736571

if [ $# -eq 0 ]; then
    echo "Usage: $0 <hex_key>"
    echo ""
    echo "Examples:"
    echo "  $0 0x0500000000000000030000000000000000726f7769645f736571"
    echo "  $0 0500000000000000030000000000000000726f7769645f736571"
    exit 1
fi

KEY="$1"

# Remove 0x prefix if present
KEY="${KEY#0x}"
KEY="${KEY#0X}"

# Convert to uppercase for consistency
KEY=$(echo "$KEY" | tr '[:lower:]' '[:upper:]')

echo "=========================================="
echo "Key Decoder for Cassandra SQL"
echo "=========================================="
echo ""
echo "Raw Key (hex): $KEY"
echo ""

# Extract namespace byte (first 2 hex chars = 1 byte)
NAMESPACE="${KEY:0:2}"
NAMESPACE_DEC=$((16#$NAMESPACE))

echo "Namespace: 0x$NAMESPACE (decimal: $NAMESPACE_DEC)"

# Decode namespace
case $NAMESPACE_DEC in
    1)
        echo "  → NAMESPACE_TABLE_DATA (0x01)"
        echo ""
        
        # Extract table ID (next 16 hex chars = 8 bytes)
        TABLE_ID_HEX="${KEY:2:16}"
        TABLE_ID=$((16#$TABLE_ID_HEX))
        
        # Extract index ID (next 16 hex chars = 8 bytes)
        INDEX_ID_HEX="${KEY:18:16}"
        INDEX_ID=$((16#$INDEX_ID_HEX))
        
        echo "Table ID: $TABLE_ID (0x$TABLE_ID_HEX)"
        echo "Index ID: $INDEX_ID (0x$INDEX_ID_HEX)"
        
        if [ $INDEX_ID -eq 0 ]; then
            echo "  → PRIMARY_INDEX_ID (primary key)"
        else
            echo "  → Secondary index"
        fi
        
        # Remaining bytes are the primary key values + timestamp
        REMAINING="${KEY:34}"
        if [ -n "$REMAINING" ]; then
            echo ""
            echo "Primary Key + Timestamp (hex): $REMAINING"
            # Try to decode as timestamp at the end (last 8 bytes)
            if [ ${#REMAINING} -ge 16 ]; then
                TS_HEX="${REMAINING: -16}"
                TS=$((16#$TS_HEX))
                echo "  Timestamp (last 8 bytes): $TS"
                
                # Convert to human-readable date (microseconds since epoch)
                TS_SEC=$((TS / 1000000))
                if command -v date >/dev/null 2>&1; then
                    if date -r $TS_SEC >/dev/null 2>&1; then
                        DATE=$(date -r $TS_SEC "+%Y-%m-%d %H:%M:%S")
                        echo "  → $DATE"
                    fi
                fi
            fi
        fi
        ;;
        
    2)
        echo "  → NAMESPACE_SCHEMA (0x02)"
        echo ""
        
        # Extract table ID (next 16 hex chars = 8 bytes)
        TABLE_ID_HEX="${KEY:2:16}"
        TABLE_ID=$((16#$TABLE_ID_HEX))
        
        # Extract reserved (next 16 hex chars = 8 bytes)
        RESERVED_HEX="${KEY:18:16}"
        
        echo "Table ID: $TABLE_ID (0x$TABLE_ID_HEX)"
        echo "Reserved: 0x$RESERVED_HEX"
        
        # Remaining bytes are metadata type
        REMAINING="${KEY:34}"
        if [ -n "$REMAINING" ]; then
            echo ""
            echo "Metadata Type (hex): $REMAINING"
            # Try to decode as ASCII
            METADATA_ASCII=$(echo "$REMAINING" | xxd -r -p 2>/dev/null || echo "")
            if [ -n "$METADATA_ASCII" ]; then
                echo "  → ASCII: '$METADATA_ASCII'"
            fi
        fi
        ;;
        
    3)
        echo "  → NAMESPACE_INDEX (0x03)"
        echo ""
        
        # Extract table ID (next 16 hex chars = 8 bytes)
        TABLE_ID_HEX="${KEY:2:16}"
        TABLE_ID=$((16#$TABLE_ID_HEX))
        
        # Extract index ID (next 16 hex chars = 8 bytes)
        INDEX_ID_HEX="${KEY:18:16}"
        INDEX_ID=$((16#$INDEX_ID_HEX))
        
        echo "Table ID: $TABLE_ID (0x$TABLE_ID_HEX)"
        echo "Index ID: $INDEX_ID (0x$INDEX_ID_HEX)"
        
        # Remaining bytes are index key + primary key + timestamp
        REMAINING="${KEY:34}"
        if [ -n "$REMAINING" ]; then
            echo ""
            echo "Index Key + PK + Timestamp (hex): $REMAINING"
        fi
        ;;
        
    4)
        echo "  → NAMESPACE_TRANSACTION (0x04)"
        echo ""
        
        # Extract transaction ID (UUID, 32 hex chars = 16 bytes)
        if [ ${#KEY} -ge 34 ]; then
            TX_ID="${KEY:2:32}"
            # Format as UUID: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
            UUID="${TX_ID:0:8}-${TX_ID:8:4}-${TX_ID:12:4}-${TX_ID:16:4}-${TX_ID:20:12}"
            echo "Transaction ID: $UUID"
        fi
        ;;
        
    5)
        echo "  → NAMESPACE_SEQUENCE (0x05)"
        echo ""
        
        # Extract table ID (next 16 hex chars = 8 bytes)
        TABLE_ID_HEX="${KEY:2:16}"
        TABLE_ID=$((16#$TABLE_ID_HEX))
        
        # Extract reserved (next 16 hex chars = 8 bytes)
        RESERVED_HEX="${KEY:18:16}"
        
        echo "Table ID: $TABLE_ID (0x$TABLE_ID_HEX)"
        echo "Reserved: 0x$RESERVED_HEX"
        
        # Remaining bytes are sequence name (UTF-8)
        REMAINING="${KEY:34}"
        if [ -n "$REMAINING" ]; then
            echo ""
            echo "Sequence Name (hex): $REMAINING"
            # Decode as UTF-8
            SEQ_NAME=$(echo "$REMAINING" | xxd -r -p 2>/dev/null || echo "")
            if [ -n "$SEQ_NAME" ]; then
                echo "  → '$SEQ_NAME'"
            fi
        fi
        ;;
        
    6)
        echo "  → NAMESPACE_LOCK (0x06)"
        echo ""
        
        # This is for kv_locks table
        # Key format: just the data key being locked
        REMAINING="${KEY:2}"
        echo "Locked Key (hex): $REMAINING"
        echo ""
        echo "Recursively decoding locked key:"
        echo "----------------------------------------"
        # Recursive call to decode the locked key
        $0 "$REMAINING"
        ;;
        
    7)
        echo "  → NAMESPACE_WRITE (0x07)"
        echo ""
        
        # This is for kv_writes table (buffered writes)
        # Extract transaction ID (UUID, 32 hex chars = 16 bytes)
        if [ ${#KEY} -ge 34 ]; then
            TX_ID="${KEY:2:32}"
            # Format as UUID
            UUID="${TX_ID:0:8}-${TX_ID:8:4}-${TX_ID:12:4}-${TX_ID:16:4}-${TX_ID:20:12}"
            echo "Transaction ID: $UUID"
            
            # Remaining is the data key
            REMAINING="${KEY:34}"
            if [ -n "$REMAINING" ]; then
                echo ""
                echo "Data Key (hex): $REMAINING"
                echo ""
                echo "Recursively decoding data key:"
                echo "----------------------------------------"
                # Recursive call to decode the data key
                $0 "$REMAINING"
            fi
        fi
        ;;
        
    *)
        echo "  → UNKNOWN NAMESPACE"
        ;;
esac

echo ""
echo "=========================================="
echo ""
echo "Namespace Reference:"
echo "  0x01 = TABLE_DATA    (actual row data)"
echo "  0x02 = SCHEMA        (table metadata)"
echo "  0x03 = INDEX         (secondary index entries)"
echo "  0x04 = TRANSACTION   (transaction metadata)"
echo "  0x05 = SEQUENCE      (sequence values)"
echo "  0x06 = LOCK          (transaction locks)"
echo "  0x07 = WRITE         (buffered writes)"
echo ""


