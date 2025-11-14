#!/bin/bash

# ============================================================================
# CassandraSQL E-Commerce Demo
# ============================================================================
# This script demonstrates the full SQL capabilities of CassandraSQL KV mode
# through a realistic e-commerce scenario.
#
# Features Demonstrated:
# - DDL: CREATE TABLE, ALTER TABLE, CREATE INDEX, CREATE SEQUENCE
# - DML: INSERT, UPDATE, DELETE with transactions
# - Queries: SELECT, JOIN, aggregations, subqueries, window functions
# - Advanced: VIEWs, MATERIALIZED VIEWs, constraints, enums, arrays
# - Analytics: Complex aggregations, ORDER BY, LIMIT
# - ACID: Multi-statement transactions with BEGIN/COMMIT
# ============================================================================

set -e  # Exit on error

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# Configuration
PSQL_HOST="${PSQL_HOST:-localhost}"
PSQL_PORT="${PSQL_PORT:-5432}"
PSQL_DB="${PSQL_DB:-cassandra_sql}"

# Helper function to execute SQL
execute_sql() {
    local sql="$1"
    local description="$2"
    
    echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${YELLOW}📝 $description${NC}"
    echo -e "${BLUE}SQL:${NC} $sql"
    echo ""
    
    psql -h "$PSQL_HOST" -p "$PSQL_PORT" -d "$PSQL_DB" -c "$sql"
    
    if [ $? -eq 0 ]; then
        echo -e "${GREEN}✅ Success${NC}\n"
    else
        echo -e "${RED}❌ Failed${NC}\n"
        exit 1
    fi
}

# Helper function to execute a transaction (multiple SQL statements in one connection)
execute_transaction() {
    local description="$1"
    shift  # Remove first argument, rest are SQL statements
    
    echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${YELLOW}📝 $description${NC}"
    echo -e "${BLUE}Transaction with ${#@} statements${NC}"
    echo ""
    
    # Build the full SQL by concatenating all statements
    local full_sql=""
    for sql in "$@"; do
        full_sql="${full_sql}${sql} "
    done

    echo -e "${BLUE}SQL:${NC} $full_sql ${NC}"
    
    # Execute all statements in a single psql connection
    psql -h "$PSQL_HOST" -p "$PSQL_PORT" -d "$PSQL_DB" -c "$full_sql"
    
    if [ $? -eq 0 ]; then
        echo -e "${GREEN}✅ Transaction committed${NC}\n"
    else
        echo -e "${RED}❌ Transaction failed${NC}\n"
        exit 1
    fi
}

# Helper function to execute SQL from file
execute_sql_file() {
    local file="$1"
    local description="$2"
    
    echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${YELLOW}📝 $description${NC}"
    echo -e "${BLUE}Executing from file:${NC} $file"
    echo ""
    
    psql -h "$PSQL_HOST" -p "$PSQL_PORT" -d "$PSQL_DB" -f "$file"
    
    if [ $? -eq 0 ]; then
        echo -e "${GREEN}✅ Success${NC}\n"
    else
        echo -e "${RED}❌ Failed${NC}\n"
        exit 1
    fi
}

# Header
clear
echo -e "${GREEN}"
cat << "EOF"
╔═══════════════════════════════════════════════════════════════════════════╗
║                                                                           ║
║                   CassandraSQL E-Commerce Demo                            ║
║                                                                           ║
║   Demonstrating SQL capabilities through a realistic online store        ║
║                                                                           ║
╚═══════════════════════════════════════════════════════════════════════════╝
EOF
echo -e "${NC}\n"

sleep 2

# ============================================================================
# PHASE 1: SCHEMA CREATION
# ============================================================================

echo -e "${GREEN}═══════════════════════════════════════════════════════════════${NC}"
echo -e "${GREEN}  PHASE 1: Schema Creation (DDL)${NC}"
echo -e "${GREEN}═══════════════════════════════════════════════════════════════${NC}\n"

# Create ENUM types
execute_sql "CREATE TYPE order_status AS ENUM ('pending', 'processing', 'shipped', 'delivered', 'cancelled');" \
    "Creating ENUM type for order status"

execute_sql "CREATE TYPE payment_method AS ENUM ('credit_card', 'debit_card', 'paypal', 'bank_transfer');" \
    "Creating ENUM type for payment methods"

# Create sequences for auto-incrementing IDs
execute_sql "CREATE SEQUENCE customer_id_seq START WITH 1000 INCREMENT BY 1;" \
    "Creating sequence for customer IDs"

execute_sql "CREATE SEQUENCE product_id_seq START WITH 2000 INCREMENT BY 1;" \
    "Creating sequence for product IDs"

execute_sql "CREATE SEQUENCE order_id_seq START WITH 5000 INCREMENT BY 1;" \
    "Creating sequence for order IDs"

# Create customers table
execute_sql "CREATE TABLE customers (
    customer_id BIGINT PRIMARY KEY,
    email TEXT UNIQUE NOT NULL,
    first_name TEXT NOT NULL,
    last_name TEXT NOT NULL,
    phone TEXT,
    address TEXT,
    city TEXT,
    state TEXT,
    zip_code TEXT,
    country TEXT DEFAULT 'USA',
    created_at BIGINT,
    loyalty_points INT DEFAULT 0
);" "Creating customers table"

# Create products table with inventory
execute_sql "CREATE TABLE products (
    product_id BIGINT PRIMARY KEY,
    sku TEXT UNIQUE NOT NULL,
    name TEXT NOT NULL,
    description TEXT,
    category TEXT,
    price DECIMAL(10,2) NOT NULL,
    cost DECIMAL(10,2),
    stock_quantity INT DEFAULT 0,
    reorder_level INT DEFAULT 10,
    supplier TEXT,
    tags TEXT[],
    created_at BIGINT,
    updated_at BIGINT
);" "Creating products table with DECIMAL and ARRAY types"

# Create orders table
execute_sql "CREATE TABLE orders (
    order_id BIGINT PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    order_date BIGINT NOT NULL,
    status order_status DEFAULT 'pending',
    payment_method payment_method,
    subtotal DECIMAL(10,2),
    tax DECIMAL(10,2),
    shipping DECIMAL(10,2),
    total DECIMAL(10,2),
    shipping_address TEXT,
    tracking_number TEXT,
    notes TEXT
);" "Creating orders table with ENUM and DECIMAL types"

# Add foreign key constraint
execute_sql "ALTER TABLE orders ADD CONSTRAINT fk_customer FOREIGN KEY (customer_id) REFERENCES customers(customer_id);" \
    "Adding foreign key constraint from orders to customers"

# Create order_items table
execute_sql "CREATE TABLE order_items (
    order_item_id BIGINT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    unit_price DECIMAL(10,2) NOT NULL,
    discount DECIMAL(10,2) DEFAULT 0.00,
    line_total DECIMAL(10,2)
);" "Creating order_items table"

# Add foreign key constraints
execute_sql "ALTER TABLE order_items ADD CONSTRAINT fk_order FOREIGN KEY (order_id) REFERENCES orders(order_id);" \
    "Adding foreign key constraint from order_items to orders"

execute_sql "ALTER TABLE order_items ADD CONSTRAINT fk_product FOREIGN KEY (product_id) REFERENCES products(product_id);" \
    "Adding foreign key constraint from order_items to products"

# Create reviews table
execute_sql "CREATE TABLE reviews (
    review_id BIGINT PRIMARY KEY,
    product_id BIGINT NOT NULL,
    customer_id BIGINT NOT NULL,
    rating INT NOT NULL,
    title TEXT,
    comment TEXT,
    helpful_count INT DEFAULT 0,
    created_at BIGINT
);" "Creating reviews table"

# Add indexes for common queries
execute_sql "CREATE INDEX idx_orders_customer ON orders(customer_id);" \
    "Creating index on orders.customer_id"

execute_sql "CREATE INDEX idx_orders_date ON orders(order_date);" \
    "Creating index on orders.order_date"

execute_sql "CREATE INDEX idx_orders_status ON orders(status);" \
    "Creating index on orders.status"

execute_sql "CREATE INDEX idx_products_category ON products(category);" \
    "Creating index on products.category"

execute_sql "CREATE INDEX idx_products_price ON products(price);" \
    "Creating index on products.price"

execute_sql "CREATE INDEX idx_reviews_product ON reviews(product_id);" \
    "Creating index on reviews.product_id"

execute_sql "CREATE INDEX idx_reviews_rating ON reviews(rating);" \
    "Creating index on reviews.rating"

# ============================================================================
# PHASE 2: DATA POPULATION
# ============================================================================

echo -e "\n${GREEN}═══════════════════════════════════════════════════════════════${NC}"
echo -e "${GREEN}  PHASE 2: Data Population (INSERT)${NC}"
echo -e "${GREEN}═══════════════════════════════════════════════════════════════${NC}\n"

# Insert customers
execute_sql "INSERT INTO customers (customer_id, email, first_name, last_name, phone, address, city, state, zip_code, created_at, loyalty_points)
VALUES 
    (1001, 'alice@example.com', 'Alice', 'Johnson', '555-0101', '123 Main St', 'Seattle', 'WA', '98101', 1704067200000, 150),
    (1002, 'bob@example.com', 'Bob', 'Smith', '555-0102', '456 Oak Ave', 'Portland', 'OR', '97201', 1704153600000, 75),
    (1003, 'carol@example.com', 'Carol', 'Williams', '555-0103', '789 Pine Rd', 'San Francisco', 'CA', '94102', 1704240000000, 200),
    (1004, 'david@example.com', 'David', 'Brown', '555-0104', '321 Elm St', 'Los Angeles', 'CA', '90001', 1704326400000, 50),
    (1005, 'eve@example.com', 'Eve', 'Davis', '555-0105', '654 Maple Dr', 'San Diego', 'CA', '92101', 1704412800000, 300);" \
    "Inserting 5 customers"

# Insert products
execute_sql "INSERT INTO products (product_id, sku, name, description, category, price, cost, stock_quantity, reorder_level, supplier, tags, created_at)
VALUES 
    (2001, 'LAPTOP-001', 'UltraBook Pro 15', 'High-performance laptop with 16GB RAM', 'Electronics', 1299.99, 899.99, 50, 10, 'TechCorp', ARRAY['laptop', 'computer', 'electronics'], 1704067200000),
    (2002, 'PHONE-001', 'SmartPhone X', 'Latest smartphone with 5G', 'Electronics', 899.99, 599.99, 100, 20, 'PhoneCo', ARRAY['phone', 'mobile', '5g'], 1704067200000),
    (2003, 'TABLET-001', 'Tablet Air', '10-inch tablet for productivity', 'Electronics', 499.99, 299.99, 75, 15, 'TechCorp', ARRAY['tablet', 'electronics'], 1704067200000),
    (2004, 'HEADPHONE-001', 'Wireless Headphones Pro', 'Noise-cancelling wireless headphones', 'Audio', 249.99, 149.99, 200, 30, 'AudioMax', ARRAY['headphones', 'audio', 'wireless'], 1704067200000),
    (2005, 'KEYBOARD-001', 'Mechanical Keyboard RGB', 'Gaming keyboard with RGB lighting', 'Accessories', 149.99, 79.99, 150, 25, 'GameGear', ARRAY['keyboard', 'gaming', 'rgb'], 1704067200000),
    (2006, 'MOUSE-001', 'Wireless Mouse Pro', 'Ergonomic wireless mouse', 'Accessories', 59.99, 29.99, 300, 50, 'TechCorp', ARRAY['mouse', 'wireless', 'ergonomic'], 1704067200000),
    (2007, 'MONITOR-001', '4K Monitor 27-inch', 'Ultra HD 4K display', 'Electronics', 399.99, 249.99, 40, 10, 'DisplayTech', ARRAY['monitor', '4k', 'display'], 1704067200000),
    (2008, 'WEBCAM-001', 'HD Webcam', '1080p webcam for video calls', 'Electronics', 79.99, 39.99, 120, 20, 'VideoPro', ARRAY['webcam', 'video', 'hd'], 1704067200000),
    (2009, 'SPEAKER-001', 'Bluetooth Speaker', 'Portable bluetooth speaker', 'Audio', 129.99, 69.99, 180, 30, 'AudioMax', ARRAY['speaker', 'bluetooth', 'portable'], 1704067200000),
    (2010, 'CHARGER-001', 'Fast Charger 65W', 'USB-C fast charger', 'Accessories', 39.99, 19.99, 500, 100, 'PowerPlus', ARRAY['charger', 'usb-c', 'fast'], 1704067200000);" \
    "Inserting 10 products with ARRAY columns"

# ============================================================================
# PHASE 3: TRANSACTIONAL OPERATIONS
# ============================================================================

echo -e "\n${GREEN}═══════════════════════════════════════════════════════════════${NC}"
echo -e "${GREEN}  PHASE 3: Transactional Operations (ACID)${NC}"
echo -e "${GREEN}═══════════════════════════════════════════════════════════════${NC}\n"

# Demonstrate a complete order transaction
echo -e "${YELLOW}Scenario: Customer Alice purchases a laptop and headphones${NC}\n"

execute_transaction "Order Transaction for Alice (laptop + headphones)" \
    "BEGIN;" \
    "INSERT INTO orders (order_id, customer_id, order_date, status, payment_method, subtotal, tax, shipping, total, shipping_address)
    VALUES (5001, 1001, 1704499200000, 'pending', 'credit_card', 1549.98, 139.50, 15.00, 1704.48, '123 Main St, Seattle, WA 98101');" \
    "INSERT INTO order_items (order_item_id, order_id, product_id, quantity, unit_price, discount, line_total)
    VALUES 
        (10001, 5001, 2001, 1, 1299.99, 0.00, 1299.99),
        (10002, 5001, 2004, 1, 249.99, 0.00, 249.99);" \
    "UPDATE products SET stock_quantity = stock_quantity - 1, updated_at = 1704499200000 WHERE product_id = 2001;" \
    "UPDATE products SET stock_quantity = stock_quantity - 1, updated_at = 1704499200000 WHERE product_id = 2004;" \
    "UPDATE customers SET loyalty_points = loyalty_points + 170 WHERE customer_id = 1001;" \
    "COMMIT;"

# Another order
echo -e "\n${YELLOW}Scenario: Customer Bob purchases a tablet and mouse${NC}\n"

execute_transaction "Order Transaction for Bob (tablet + mouse)" \
    "BEGIN;" \
    "INSERT INTO orders (order_id, customer_id, order_date, status, payment_method, subtotal, tax, shipping, total, shipping_address)
    VALUES (5002, 1002, 1704585600000, 'pending', 'paypal', 559.98, 50.40, 10.00, 620.38, '456 Oak Ave, Portland, OR 97201');" \
    "INSERT INTO order_items (order_item_id, order_id, product_id, quantity, unit_price, discount, line_total)
    VALUES 
        (10003, 5002, 2003, 1, 499.99, 0.00, 499.99),
        (10004, 5002, 2006, 1, 59.99, 0.00, 59.99);" \
    "UPDATE products SET stock_quantity = stock_quantity - 1, updated_at = 1704585600000 WHERE product_id = 2003;" \
    "UPDATE products SET stock_quantity = stock_quantity - 1, updated_at = 1704585600000 WHERE product_id = 2006;" \
    "UPDATE customers SET loyalty_points = loyalty_points + 62 WHERE customer_id = 1002;" \
    "COMMIT;"

# Update order status
execute_sql "UPDATE orders SET status = 'processing', tracking_number = 'TRACK123456' WHERE order_id = 5001;" \
    "Updating order #5001 status to processing"

execute_sql "UPDATE orders SET status = 'shipped', tracking_number = 'TRACK789012' WHERE order_id = 5002;" \
    "Updating order #5002 status to shipped"

# Add reviews
execute_sql "INSERT INTO reviews (review_id, product_id, customer_id, rating, title, comment, helpful_count, created_at)
VALUES 
    (3001, 2001, 1001, 5, 'Excellent laptop!', 'Fast performance, great battery life. Highly recommended!', 15, 1704672000000),
    (3002, 2004, 1001, 4, 'Good headphones', 'Sound quality is great, but a bit pricey.', 8, 1704672000000),
    (3003, 2003, 1002, 5, 'Perfect tablet', 'Love it for reading and browsing. Screen is beautiful.', 12, 1704758400000),
    (3004, 2006, 1002, 5, 'Best mouse ever', 'Comfortable and responsive. Great value!', 20, 1704758400000);" \
    "Adding product reviews"

# ============================================================================
# PHASE 4: COMPLEX QUERIES
# ============================================================================

echo -e "\n${GREEN}═══════════════════════════════════════════════════════════════${NC}"
echo -e "${GREEN}  PHASE 4: Complex Queries (SELECT, JOIN, Aggregations)${NC}"
echo -e "${GREEN}═══════════════════════════════════════════════════════════════${NC}\n"

# Simple SELECT with WHERE
execute_sql "SELECT product_id, name, category, price, stock_quantity 
FROM products 
WHERE category = 'Electronics' AND price < 1000 
ORDER BY price DESC;" \
    "Query: Electronics under \$1000, ordered by price"

# JOIN query
execute_sql "SELECT 
    o.order_id,
    c.first_name || ' ' || c.last_name AS customer_name,
    c.email,
    o.order_date,
    o.status,
    o.total
FROM orders o
INNER JOIN customers c ON o.customer_id = c.customer_id
ORDER BY o.order_date DESC;" \
    "Query: Orders with customer details (INNER JOIN)"

# Multi-way JOIN
execute_sql "SELECT 
    c.first_name || ' ' || c.last_name AS customer_name,
    p.name AS product_name,
    p.category,
    oi.quantity,
    oi.unit_price,
    oi.line_total,
    o.order_date,
    o.status
FROM order_items oi
INNER JOIN orders o ON oi.order_id = o.order_id
INNER JOIN customers c ON o.customer_id = c.customer_id
INNER JOIN products p ON oi.product_id = p.product_id
ORDER BY o.order_date DESC, oi.order_item_id;" \
    "Query: Complete order details (3-way JOIN)"

# Aggregation query
execute_sql "SELECT 
    category,
    COUNT(*) AS product_count,
    AVG(price) AS avg_price,
    MIN(price) AS min_price,
    MAX(price) AS max_price,
    SUM(stock_quantity) AS total_stock
FROM products
GROUP BY category
ORDER BY avg_price DESC;" \
    "Query: Product statistics by category (GROUP BY with aggregations)"

# Subquery
execute_sql "SELECT 
    name,
    price,
    stock_quantity
FROM products
WHERE price > (SELECT AVG(price) FROM products)
ORDER BY price DESC;" \
    "Query: Products priced above average (subquery)"

# Complex aggregation with HAVING
execute_sql "SELECT 
    p.category,
    COUNT(DISTINCT oi.order_id) AS order_count,
    SUM(oi.quantity) AS total_units_sold,
    SUM(oi.line_total) AS total_revenue
FROM order_items oi
INNER JOIN products p ON oi.product_id = p.product_id
GROUP BY p.category
HAVING SUM(oi.line_total) > 100
ORDER BY total_revenue DESC;" \
    "Query: Sales by category with HAVING clause"

# Top customers by spending
execute_sql "SELECT 
    c.customer_id,
    c.first_name || ' ' || c.last_name AS customer_name,
    c.email,
    COUNT(o.order_id) AS order_count,
    SUM(o.total) AS total_spent,
    c.loyalty_points
FROM customers c
LEFT JOIN orders o ON c.customer_id = o.customer_id
GROUP BY c.customer_id, c.first_name, c.last_name, c.email, c.loyalty_points
ORDER BY total_spent DESC NULLS LAST
LIMIT 5;" \
    "Query: Top 5 customers by spending (LEFT JOIN with LIMIT)"

# Product reviews with ratings
execute_sql "SELECT 
    p.name AS product_name,
    p.category,
    COUNT(r.review_id) AS review_count,
    AVG(r.rating) AS avg_rating,
    SUM(r.helpful_count) AS total_helpful
FROM products p
LEFT JOIN reviews r ON p.product_id = r.product_id
GROUP BY p.product_id, p.name, p.category
HAVING COUNT(r.review_id) > 0
ORDER BY avg_rating DESC, review_count DESC;" \
    "Query: Product ratings (LEFT JOIN with aggregations)"

# ============================================================================
# PHASE 5: VIEWS AND MATERIALIZED VIEWS
# ============================================================================

echo -e "\n${GREEN}═══════════════════════════════════════════════════════════════${NC}"
echo -e "${GREEN}  PHASE 5: Views and Materialized Views (Analytics)${NC}"
echo -e "${GREEN}═══════════════════════════════════════════════════════════════${NC}\n"

# Create a virtual view for customer order summary
execute_sql "CREATE VIEW customer_order_summary AS
SELECT 
    c.customer_id,
    c.first_name || ' ' || c.last_name AS customer_name,
    c.email,
    c.loyalty_points,
    COUNT(o.order_id) AS total_orders,
    SUM(o.total) AS lifetime_value,
    MAX(o.order_date) AS last_order_date
FROM customers c
LEFT JOIN orders o ON c.customer_id = o.customer_id
GROUP BY c.customer_id, c.first_name, c.last_name, c.email, c.loyalty_points;" \
    "Creating virtual VIEW: customer_order_summary"

# Query the view
execute_sql "SELECT * FROM customer_order_summary ORDER BY lifetime_value DESC NULLS LAST;" \
    "Querying virtual view"

# Create materialized view for product performance
execute_sql "CREATE MATERIALIZED VIEW product_performance AS
SELECT 
    p.product_id,
    p.name,
    p.category,
    p.price,
    p.stock_quantity,
    COALESCE(SUM(oi.quantity), 0) AS units_sold,
    COALESCE(SUM(oi.line_total), 0) AS revenue,
    COALESCE(AVG(r.rating), 0) AS avg_rating,
    COALESCE(COUNT(DISTINCT r.review_id), 0) AS review_count
FROM products p
LEFT JOIN order_items oi ON p.product_id = oi.product_id
LEFT JOIN reviews r ON p.product_id = r.product_id
GROUP BY p.product_id, p.name, p.category, p.price, p.stock_quantity;" \
    "Creating MATERIALIZED VIEW: product_performance"

# Query the materialized view
execute_sql "SELECT 
    name,
    category,
    price,
    units_sold,
    revenue,
    avg_rating,
    review_count
FROM product_performance
ORDER BY revenue DESC;" \
    "Querying materialized view before REFRESH: Top selling products"

# Populate the materialized view with a REFRESH
execute_sql "REFRESH MATERIALIZED VIEW product_performance;" \
    "Populating materialized view with REFRESH"

# Query the materialized view
execute_sql "SELECT 
    name,
    category,
    price,
    units_sold,
    revenue,
    avg_rating,
    review_count
FROM product_performance
ORDER BY revenue DESC;" \
    "Querying materialized view after REFRESH: Top selling products"

# Create index on materialized view
execute_sql "CREATE INDEX idx_product_perf_revenue ON product_performance(revenue);" \
    "Creating index on materialized view"

# Create materialized view for daily sales
execute_sql "CREATE MATERIALIZED VIEW daily_sales_summary AS
SELECT 
    o.order_date,
    COUNT(o.order_id) AS order_count,
    SUM(o.subtotal) AS subtotal,
    SUM(o.tax) AS tax,
    SUM(o.shipping) AS shipping,
    SUM(o.total) AS total_revenue,
    AVG(o.total) AS avg_order_value
FROM orders o
GROUP BY o.order_date;" \
    "Creating MATERIALIZED VIEW: daily_sales_summary"

# Query daily sales
execute_sql "SELECT * FROM daily_sales_summary ORDER BY order_date DESC;" \
    "Querying materialized view: Daily sales"

# Demonstrate REFRESH MATERIALIZED VIEW
echo -e "\n${YELLOW}Adding more data and refreshing materialized views...${NC}\n"

execute_sql "INSERT INTO orders (order_id, customer_id, order_date, status, payment_method, subtotal, tax, shipping, total, shipping_address)
VALUES (5003, 1003, 1704844800000, 'pending', 'credit_card', 899.99, 81.00, 12.00, 992.99, '789 Pine Rd, San Francisco, CA 94102');" \
    "Adding new order"

execute_sql "INSERT INTO order_items (order_item_id, order_id, product_id, quantity, unit_price, discount, line_total)
VALUES (10005, 5003, 2002, 1, 899.99, 0.00, 899.99);" \
    "Adding order item"

execute_sql "UPDATE products SET stock_quantity = stock_quantity - 1 WHERE product_id = 2002;" \
    "Updating inventory"

execute_sql "REFRESH MATERIALIZED VIEW product_performance;" \
    "Refreshing materialized view: product_performance"

execute_sql "REFRESH MATERIALIZED VIEW daily_sales_summary;" \
    "Refreshing materialized view: daily_sales_summary"

# Query refreshed data
execute_sql "SELECT * FROM product_performance WHERE product_id = 2002;" \
    "Verifying refreshed data for SmartPhone X"

# ============================================================================
# PHASE 6: ADVANCED FEATURES
# ============================================================================

echo -e "\n${GREEN}═══════════════════════════════════════════════════════════════${NC}"
echo -e "${GREEN}  PHASE 6: Advanced Features${NC}"
echo -e "${GREEN}═══════════════════════════════════════════════════════════════${NC}\n"

# Arithmetic expressions
execute_sql "SELECT 
    name,
    price,
    cost,
    price - cost AS profit,
    ROUND((price - cost) / price * 100, 2) AS profit_margin_pct,
    stock_quantity * price AS inventory_value
FROM products
WHERE stock_quantity > 0
ORDER BY profit DESC
LIMIT 5;" \
    "Query: Product profitability with arithmetic expressions"

# Math functions
execute_sql "SELECT 
    name,
    price,
    ROUND(price, 0) AS rounded_price,
    CEIL(price) AS ceiling_price,
    FLOOR(price) AS floor_price,
    POWER(price, 2) AS price_squared,
    SQRT(price) AS price_sqrt
FROM products
WHERE category = 'Electronics'
LIMIT 3;" \
    "Query: Math functions demonstration"

# String concatenation and functions
execute_sql "SELECT 
    customer_id,
    first_name || ' ' || last_name AS full_name,
    email,
    city || ', ' || state AS location
FROM customers
ORDER BY last_name, first_name;" \
    "Query: String concatenation"

# CASE expression
execute_sql "SELECT 
    name,
    stock_quantity,
    reorder_level,
    CASE 
        WHEN stock_quantity = 0 THEN 'OUT OF STOCK'
        WHEN stock_quantity <= reorder_level THEN 'LOW STOCK'
        WHEN stock_quantity <= reorder_level * 2 THEN 'MEDIUM STOCK'
        ELSE 'IN STOCK'
    END AS stock_status
FROM products
ORDER BY stock_quantity;" \
    "Query: CASE expression for stock status"

# EXPLAIN query plan
execute_sql "EXPLAIN SELECT 
    c.first_name,
    c.last_name,
    o.order_id,
    o.total
FROM customers c
INNER JOIN orders o ON c.customer_id = o.customer_id
WHERE o.status = 'shipped';" \
    "EXPLAIN: Query plan for filtered JOIN"

# EXPLAIN ANALYZE
execute_sql "EXPLAIN ANALYZE SELECT 
    product_id,
    name,
    price
FROM products
WHERE category = 'Electronics'
ORDER BY price DESC
LIMIT 5;" \
    "EXPLAIN ANALYZE: Execution plan with statistics"

# ============================================================================
# PHASE 7: DATA MODIFICATION
# ============================================================================

echo -e "\n${GREEN}═══════════════════════════════════════════════════════════════${NC}"
echo -e "${GREEN}  PHASE 7: Data Modification (UPDATE, DELETE)${NC}"
echo -e "${GREEN}═══════════════════════════════════════════════════════════════${NC}\n"

# UPDATE with arithmetic
execute_sql "UPDATE products 
SET price = price * 1.10 
WHERE category = 'Accessories';" \
    "UPDATE: 10% price increase for accessories"

# Verify update
execute_sql "SELECT name, category, price FROM products WHERE category = 'Accessories';" \
    "Verifying price updates"

# UPDATE with subquery
execute_sql "UPDATE customers 
SET loyalty_points = loyalty_points + 50 
WHERE customer_id IN (
    SELECT DISTINCT customer_id 
    FROM orders 
    WHERE status = 'delivered'
);" \
    "UPDATE: Bonus points for customers with delivered orders"

# DELETE example
execute_sql "DELETE FROM reviews WHERE rating < 3 AND helpful_count = 0;" \
    "DELETE: Removing unhelpful low-rated reviews"

# ============================================================================
# PHASE 8: ANALYTICS QUERIES
# ============================================================================

echo -e "\n${GREEN}═══════════════════════════════════════════════════════════════${NC}"
echo -e "${GREEN}  PHASE 8: Analytics & Reporting${NC}"
echo -e "${GREEN}═══════════════════════════════════════════════════════════════${NC}\n"

# Revenue by category
execute_sql "SELECT 
    p.category,
    COUNT(DISTINCT oi.order_id) AS orders,
    SUM(oi.quantity) AS units_sold,
    SUM(oi.line_total) AS revenue,
    AVG(oi.unit_price) AS avg_price
FROM order_items oi
INNER JOIN products p ON oi.product_id = p.product_id
GROUP BY p.category
ORDER BY revenue DESC;" \
    "Analytics: Revenue by product category"

# Customer lifetime value analysis
execute_sql "SELECT 
    CASE 
        WHEN total_spent >= 2000 THEN 'VIP'
        WHEN total_spent >= 1000 THEN 'Premium'
        WHEN total_spent >= 500 THEN 'Regular'
        ELSE 'New'
    END AS customer_tier,
    COUNT(*) AS customer_count,
    AVG(total_spent) AS avg_spent,
    SUM(total_spent) AS total_revenue
FROM (
    SELECT 
        c.customer_id,
        COALESCE(SUM(o.total), 0) AS total_spent
    FROM customers c
    LEFT JOIN orders o ON c.customer_id = o.customer_id
    GROUP BY c.customer_id
) AS customer_spending
GROUP BY customer_tier
ORDER BY avg_spent DESC;" \
    "Analytics: Customer segmentation by spending"

# Inventory report
execute_sql "SELECT 
    category,
    COUNT(*) AS product_count,
    SUM(stock_quantity) AS total_units,
    SUM(stock_quantity * price) AS inventory_value,
    SUM(CASE WHEN stock_quantity <= reorder_level THEN 1 ELSE 0 END) AS low_stock_count
FROM products
GROUP BY category
ORDER BY inventory_value DESC;" \
    "Analytics: Inventory report by category"

# Order status distribution
execute_sql "SELECT 
    status,
    COUNT(*) AS order_count,
    SUM(total) AS total_value,
    AVG(total) AS avg_order_value
FROM orders
GROUP BY status
ORDER BY order_count DESC;" \
    "Analytics: Order status distribution"

# ============================================================================
# SUMMARY
# ============================================================================

echo -e "\n${GREEN}═══════════════════════════════════════════════════════════════${NC}"
echo -e "${GREEN}  DEMO COMPLETE!${NC}"
echo -e "${GREEN}═══════════════════════════════════════════════════════════════${NC}\n"

echo -e "${CYAN}Summary of demonstrated features:${NC}"
echo -e "  ✅ DDL: CREATE TABLE, ALTER TABLE, CREATE INDEX, CREATE SEQUENCE"
echo -e "  ✅ Data Types: BIGINT, TEXT, INT, DECIMAL, ENUM, ARRAY"
echo -e "  ✅ Constraints: PRIMARY KEY, FOREIGN KEY, UNIQUE, NOT NULL, DEFAULT"
echo -e "  ✅ DML: INSERT, UPDATE, DELETE"
echo -e "  ✅ Transactions: BEGIN, COMMIT with ACID guarantees"
echo -e "  ✅ Queries: SELECT, WHERE, ORDER BY, LIMIT"
echo -e "  ✅ JOINs: INNER JOIN, LEFT JOIN, multi-way JOINs"
echo -e "  ✅ Aggregations: COUNT, SUM, AVG, MIN, MAX, GROUP BY, HAVING"
echo -e "  ✅ Subqueries: Scalar and table subqueries"
echo -e "  ✅ Expressions: Arithmetic, string concatenation, CASE"
echo -e "  ✅ Functions: Math functions (ROUND, CEIL, FLOOR, POWER, SQRT)"
echo -e "  ✅ VIEWs: Virtual views with query rewriting"
echo -e "  ✅ MATERIALIZED VIEWs: Pre-computed results with REFRESH"
echo -e "  ✅ Indexes: Single and multi-column indexes, indexes on materialized views"
echo -e "  ✅ Query Planning: EXPLAIN and EXPLAIN ANALYZE"
echo -e "  ✅ Analytics: Complex reporting queries"
echo ""

echo -e "${YELLOW}📊 Database Statistics:${NC}"
execute_sql "SELECT 
    'Customers' AS table_name, COUNT(*) AS row_count FROM customers
    UNION ALL
    SELECT 'Products', COUNT(*) FROM products
    UNION ALL
    SELECT 'Orders', COUNT(*) FROM orders
    UNION ALL
    SELECT 'Order Items', COUNT(*) FROM order_items
    UNION ALL
    SELECT 'Reviews', COUNT(*) FROM reviews;" \
    "Final row counts"

echo -e "\n${GREEN}🎉 Demo completed successfully!${NC}\n"



