[3J[H[2J[0;32m
╔═══════════════════════════════════════════════════════════════════════════╗
║                                                                           ║
║                   CassandraSQL E-Commerce Demo                            ║
║                                                                           ║
║   Demonstrating SQL capabilities through a realistic online store        ║
║                                                                           ║
╚═══════════════════════════════════════════════════════════════════════════╝
[0m

[0;32m═══════════════════════════════════════════════════════════════[0m
[0;32m  PHASE 1: Schema Creation (DDL)[0m
[0;32m═══════════════════════════════════════════════════════════════[0m

[0;36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━[0m
[1;33m📝 Creating ENUM type for order status[0m
[0;34mSQL:[0m CREATE TYPE order_status AS ENUM ('pending', 'processing', 'shipped', 'delivered', 'cancelled');

OK
[0;32m✅ Success[0m

[0;36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━[0m
[1;33m📝 Creating ENUM type for payment methods[0m
[0;34mSQL:[0m CREATE TYPE payment_method AS ENUM ('credit_card', 'debit_card', 'paypal', 'bank_transfer');

OK
[0;32m✅ Success[0m

[0;36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━[0m
[1;33m📝 Creating sequence for customer IDs[0m
[0;34mSQL:[0m CREATE SEQUENCE customer_id_seq START WITH 1000 INCREMENT BY 1;

OK
[0;32m✅ Success[0m

[0;36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━[0m
[1;33m📝 Creating sequence for product IDs[0m
[0;34mSQL:[0m CREATE SEQUENCE product_id_seq START WITH 2000 INCREMENT BY 1;

OK
[0;32m✅ Success[0m

[0;36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━[0m
[1;33m📝 Creating sequence for order IDs[0m
[0;34mSQL:[0m CREATE SEQUENCE order_id_seq START WITH 5000 INCREMENT BY 1;

OK
[0;32m✅ Success[0m

[0;36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━[0m
[1;33m📝 Creating customers table[0m
[0;34mSQL:[0m CREATE TABLE customers (
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
);

CREATE TABLE
[0;32m✅ Success[0m

[0;36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━[0m
[1;33m📝 Creating products table with DECIMAL and ARRAY types[0m
[0;34mSQL:[0m CREATE TABLE products (
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
);

CREATE TABLE
[0;32m✅ Success[0m

[0;36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━[0m
[1;33m📝 Creating orders table with ENUM and DECIMAL types[0m
[0;34mSQL:[0m CREATE TABLE orders (
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
);

CREATE TABLE
[0;32m✅ Success[0m

[0;36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━[0m
[1;33m📝 Adding foreign key constraint from orders to customers[0m
[0;34mSQL:[0m ALTER TABLE orders ADD CONSTRAINT fk_customer FOREIGN KEY (customer_id) REFERENCES customers(customer_id);

OK
[0;32m✅ Success[0m

[0;36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━[0m
[1;33m📝 Creating order_items table[0m
[0;34mSQL:[0m CREATE TABLE order_items (
    order_item_id BIGINT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    unit_price DECIMAL(10,2) NOT NULL,
    discount DECIMAL(10,2) DEFAULT 0.00,
    line_total DECIMAL(10,2)
);

CREATE TABLE
[0;32m✅ Success[0m

[0;36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━[0m
[1;33m📝 Adding foreign key constraint from order_items to orders[0m
[0;34mSQL:[0m ALTER TABLE order_items ADD CONSTRAINT fk_order FOREIGN KEY (order_id) REFERENCES orders(order_id);

OK
[0;32m✅ Success[0m

[0;36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━[0m
[1;33m📝 Adding foreign key constraint from order_items to products[0m
[0;34mSQL:[0m ALTER TABLE order_items ADD CONSTRAINT fk_product FOREIGN KEY (product_id) REFERENCES products(product_id);

OK
[0;32m✅ Success[0m

[0;36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━[0m
[1;33m📝 Creating reviews table[0m
[0;34mSQL:[0m CREATE TABLE reviews (
    review_id BIGINT PRIMARY KEY,
    product_id BIGINT NOT NULL,
    customer_id BIGINT NOT NULL,
    rating INT NOT NULL,
    title TEXT,
    comment TEXT,
    helpful_count INT DEFAULT 0,
    created_at BIGINT
);

CREATE TABLE
[0;32m✅ Success[0m

[0;36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━[0m
[1;33m📝 Creating index on orders.customer_id[0m
[0;34mSQL:[0m CREATE INDEX idx_orders_customer ON orders(customer_id);

CREATE INDEX
[0;32m✅ Success[0m

[0;36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━[0m
[1;33m📝 Creating index on orders.order_date[0m
[0;34mSQL:[0m CREATE INDEX idx_orders_date ON orders(order_date);

CREATE INDEX
[0;32m✅ Success[0m

[0;36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━[0m
[1;33m📝 Creating index on orders.status[0m
[0;34mSQL:[0m CREATE INDEX idx_orders_status ON orders(status);

CREATE INDEX
[0;32m✅ Success[0m

[0;36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━[0m
[1;33m📝 Creating index on products.category[0m
[0;34mSQL:[0m CREATE INDEX idx_products_category ON products(category);

CREATE INDEX
[0;32m✅ Success[0m

[0;36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━[0m
[1;33m📝 Creating index on products.price[0m
[0;34mSQL:[0m CREATE INDEX idx_products_price ON products(price);

CREATE INDEX
[0;32m✅ Success[0m

[0;36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━[0m
[1;33m📝 Creating index on reviews.product_id[0m
[0;34mSQL:[0m CREATE INDEX idx_reviews_product ON reviews(product_id);

CREATE INDEX
[0;32m✅ Success[0m

[0;36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━[0m
[1;33m📝 Creating index on reviews.rating[0m
[0;34mSQL:[0m CREATE INDEX idx_reviews_rating ON reviews(rating);

CREATE INDEX
[0;32m✅ Success[0m


[0;32m═══════════════════════════════════════════════════════════════[0m
[0;32m  PHASE 2: Data Population (INSERT)[0m
[0;32m═══════════════════════════════════════════════════════════════[0m

[0;36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━[0m
[1;33m📝 Inserting 5 customers[0m
[0;34mSQL:[0m INSERT INTO customers (customer_id, email, first_name, last_name, phone, address, city, state, zip_code, created_at, loyalty_points)
VALUES 
    (1001, 'alice@example.com', 'Alice', 'Johnson', '555-0101', '123 Main St', 'Seattle', 'WA', '98101', 1704067200000, 150),
    (1002, 'bob@example.com', 'Bob', 'Smith', '555-0102', '456 Oak Ave', 'Portland', 'OR', '97201', 1704153600000, 75),
    (1003, 'carol@example.com', 'Carol', 'Williams', '555-0103', '789 Pine Rd', 'San Francisco', 'CA', '94102', 1704240000000, 200),
    (1004, 'david@example.com', 'David', 'Brown', '555-0104', '321 Elm St', 'Los Angeles', 'CA', '90001', 1704326400000, 50),
    (1005, 'eve@example.com', 'Eve', 'Davis', '555-0105', '654 Maple Dr', 'San Diego', 'CA', '92101', 1704412800000, 300);

INSERT 0 5
[0;32m✅ Success[0m

[0;36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━[0m
[1;33m📝 Inserting 10 products with ARRAY columns[0m
[0;34mSQL:[0m INSERT INTO products (product_id, sku, name, description, category, price, cost, stock_quantity, reorder_level, supplier, tags, created_at)
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
    (2010, 'CHARGER-001', 'Fast Charger 65W', 'USB-C fast charger', 'Accessories', 39.99, 19.99, 500, 100, 'PowerPlus', ARRAY['charger', 'usb-c', 'fast'], 1704067200000);

INSERT 0 10
[0;32m✅ Success[0m


[0;32m═══════════════════════════════════════════════════════════════[0m
[0;32m  PHASE 3: Transactional Operations (ACID)[0m
[0;32m═══════════════════════════════════════════════════════════════[0m

[1;33mScenario: Customer Alice purchases a laptop and headphones[0m

[0;36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━[0m
[1;33m📝 Order Transaction for Alice (laptop + headphones)[0m
[0;34mTransaction with 7 statements[0m

[0;34mSQL:[0m BEGIN; INSERT INTO orders (order_id, customer_id, order_date, status, payment_method, subtotal, tax, shipping, total, shipping_address)
    VALUES (5001, 1001, 1704499200000, 'pending', 'credit_card', 1549.98, 139.50, 15.00, 1704.48, '123 Main St, Seattle, WA 98101'); INSERT INTO order_items (order_item_id, order_id, product_id, quantity, unit_price, discount, line_total)
    VALUES 
        (10001, 5001, 2001, 1, 1299.99, 0.00, 1299.99),
        (10002, 5001, 2004, 1, 249.99, 0.00, 249.99); UPDATE products SET stock_quantity = stock_quantity - 1, updated_at = 1704499200000 WHERE product_id = 2001; UPDATE products SET stock_quantity = stock_quantity - 1, updated_at = 1704499200000 WHERE product_id = 2004; UPDATE customers SET loyalty_points = loyalty_points + 170 WHERE customer_id = 1001; COMMIT;  [0m
OK
[0;32m✅ Transaction committed[0m


[1;33mScenario: Customer Bob purchases a tablet and mouse[0m

[0;36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━[0m
[1;33m📝 Order Transaction for Bob (tablet + mouse)[0m
[0;34mTransaction with 7 statements[0m

[0;34mSQL:[0m BEGIN; INSERT INTO orders (order_id, customer_id, order_date, status, payment_method, subtotal, tax, shipping, total, shipping_address)
    VALUES (5002, 1002, 1704585600000, 'pending', 'paypal', 559.98, 50.40, 10.00, 620.38, '456 Oak Ave, Portland, OR 97201'); INSERT INTO order_items (order_item_id, order_id, product_id, quantity, unit_price, discount, line_total)
    VALUES 
        (10003, 5002, 2003, 1, 499.99, 0.00, 499.99),
        (10004, 5002, 2006, 1, 59.99, 0.00, 59.99); UPDATE products SET stock_quantity = stock_quantity - 1, updated_at = 1704585600000 WHERE product_id = 2003; UPDATE products SET stock_quantity = stock_quantity - 1, updated_at = 1704585600000 WHERE product_id = 2006; UPDATE customers SET loyalty_points = loyalty_points + 62 WHERE customer_id = 1002; COMMIT;  [0m
OK
[0;32m✅ Transaction committed[0m

[0;36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━[0m
[1;33m📝 Updating order #5001 status to processing[0m
[0;34mSQL:[0m UPDATE orders SET status = 'processing', tracking_number = 'TRACK123456' WHERE order_id = 5001;

UPDATE 1
[0;32m✅ Success[0m

[0;36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━[0m
[1;33m📝 Updating order #5002 status to shipped[0m
[0;34mSQL:[0m UPDATE orders SET status = 'shipped', tracking_number = 'TRACK789012' WHERE order_id = 5002;

UPDATE 1
[0;32m✅ Success[0m

[0;36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━[0m
[1;33m📝 Adding product reviews[0m
[0;34mSQL:[0m INSERT INTO reviews (review_id, product_id, customer_id, rating, title, comment, helpful_count, created_at)
VALUES 
    (3001, 2001, 1001, 5, 'Excellent laptop!', 'Fast performance, great battery life. Highly recommended!', 15, 1704672000000),
    (3002, 2004, 1001, 4, 'Good headphones', 'Sound quality is great, but a bit pricey.', 8, 1704672000000),
    (3003, 2003, 1002, 5, 'Perfect tablet', 'Love it for reading and browsing. Screen is beautiful.', 12, 1704758400000),
    (3004, 2006, 1002, 5, 'Best mouse ever', 'Comfortable and responsive. Great value!', 20, 1704758400000);

INSERT 0 4
[0;32m✅ Success[0m


[0;32m═══════════════════════════════════════════════════════════════[0m
[0;32m  PHASE 4: Complex Queries (SELECT, JOIN, Aggregations)[0m
[0;32m═══════════════════════════════════════════════════════════════[0m

[0;36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━[0m
[1;33m📝 Query: Electronics under $1000, ordered by price[0m
[0;34mSQL:[0m SELECT product_id, name, category, price, stock_quantity 
FROM products 
WHERE category = 'Electronics' AND price < 1000 
ORDER BY price DESC;

 product_id |        name        |  category   | price  | stock_quantity 
------------+--------------------+-------------+--------+----------------
 2002       | SmartPhone X       | Electronics | 899.99 | 100
 2003       | Tablet Air         | Electronics | 499.99 | 74.0
 2007       | 4K Monitor 27-inch | Electronics | 399.99 | 40
 2008       | HD Webcam          | Electronics | 79.99  | 120
(4 rows)

[0;32m✅ Success[0m

[0;36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━[0m
[1;33m📝 Query: Orders with customer details (INNER JOIN)[0m
[0;34mSQL:[0m SELECT 
    o.order_id,
    c.first_name || ' ' || c.last_name AS customer_name,
    c.email,
    o.order_date,
    o.status,
    o.total
FROM orders o
INNER JOIN customers c ON o.customer_id = c.customer_id
ORDER BY o.order_date DESC;

 order_id | customer_name |       email       |  order_date   |   status   |  total  
----------+---------------+-------------------+---------------+------------+---------
 5001     | Alice Johnson | alice@example.com | 1704499200000 | processing | 1704.48
 5002     | Bob Smith     | bob@example.com   | 1704585600000 | shipped    | 620.38
(2 rows)

[0;32m✅ Success[0m

[0;36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━[0m
[1;33m📝 Query: Complete order details (3-way JOIN)[0m
[0;34mSQL:[0m SELECT 
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
ORDER BY o.order_date DESC, oi.order_item_id;

 customer_name |      product_name       |  category   | quantity | unit_price | line_total |  order_date   |   status   
---------------+-------------------------+-------------+----------+------------+------------+---------------+------------
 Alice Johnson | Wireless Headphones Pro | Audio       | 1        | 249.99     | 249.99     | 1704499200000 | processing
 Bob Smith     | Tablet Air              | Electronics | 1        | 499.99     | 499.99     | 1704585600000 | shipped
 Alice Johnson | UltraBook Pro 15        | Electronics | 1        | 1299.99    | 1299.99    | 1704499200000 | processing
 Bob Smith     | Wireless Mouse Pro      | Accessories | 1        | 59.99      | 59.99      | 1704585600000 | shipped
(4 rows)

[0;32m✅ Success[0m

[0;36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━[0m
[1;33m📝 Query: Product statistics by category (GROUP BY with aggregations)[0m
[0;34mSQL:[0m SELECT 
    category,
    COUNT(*) AS product_count,
    AVG(price) AS avg_price,
    MIN(price) AS min_price,
    MAX(price) AS max_price,
    SUM(stock_quantity) AS total_stock
FROM products
GROUP BY category
ORDER BY avg_price DESC;

  category   | product_count |     avg_price     | min_price | max_price | total_stock 
-------------+---------------+-------------------+-----------+-----------+-------------
 Accessories | 3             | 83.32333333333334 | 39.99     | 149.99    | 949.0
 Electronics | 5             | 635.99            | 79.99     | 1299.99   | 383.0
 Audio       | 2             | 189.99            | 129.99    | 249.99    | 379.0
(3 rows)

[0;32m✅ Success[0m

[0;36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━[0m
[1;33m📝 Query: Products priced above average (subquery)[0m
[0;34mSQL:[0m SELECT 
    name,
    price,
    stock_quantity
FROM products
WHERE price > (SELECT AVG(price) FROM products)
ORDER BY price DESC;

        name        |  price  | stock_quantity 
--------------------+---------+----------------
 UltraBook Pro 15   | 1299.99 | 49.0
 SmartPhone X       | 899.99  | 100
 Tablet Air         | 499.99  | 74.0
 4K Monitor 27-inch | 399.99  | 40
(4 rows)

[0;32m✅ Success[0m

[0;36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━[0m
[1;33m📝 Query: Sales by category with HAVING clause[0m
[0;34mSQL:[0m SELECT 
    p.category,
    COUNT(DISTINCT oi.order_id) AS order_count,
    SUM(oi.quantity) AS total_units_sold,
    SUM(oi.line_total) AS total_revenue
FROM order_items oi
INNER JOIN products p ON oi.product_id = p.product_id
GROUP BY p.category
HAVING SUM(oi.line_total) > 100
ORDER BY total_revenue DESC;

  category   | order_count | total_units_sold | total_revenue 
-------------+-------------+------------------+---------------
 Electronics | 2           | 2                | 1799.98
 Audio       | 1           | 1                | 249.99
(2 rows)

[0;32m✅ Success[0m

[0;36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━[0m
[1;33m📝 Query: Top 5 customers by spending (LEFT JOIN with LIMIT)[0m
[0;34mSQL:[0m SELECT 
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
LIMIT 5;

 customer_id | customer_name  |       email       | order_count | total_spent | loyalty_points 
-------------+----------------+-------------------+-------------+-------------+----------------
 1002        | Bob Smith      | bob@example.com   | 1           | 620.38      | 137.0
 1001        | Alice Johnson  | alice@example.com | 1           | 1704.48     | 320.0
             | Carol Williams | carol@example.com | 0           | 0           | 200
             | David Brown    | david@example.com | 0           | 0           | 50
             | Eve Davis      | eve@example.com   | 0           | 0           | 300
(5 rows)

[0;32m✅ Success[0m

[0;36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━[0m
[1;33m📝 Query: Product ratings (LEFT JOIN with aggregations)[0m
[0;34mSQL:[0m SELECT 
    p.name AS product_name,
    p.category,
    COUNT(r.review_id) AS review_count,
    AVG(r.rating) AS avg_rating,
    SUM(r.helpful_count) AS total_helpful
FROM products p
LEFT JOIN reviews r ON p.product_id = r.product_id
GROUP BY p.product_id, p.name, p.category
HAVING COUNT(r.review_id) > 0
ORDER BY avg_rating DESC, review_count DESC;

      product_name       |  category   | review_count | avg_rating | total_helpful 
-------------------------+-------------+--------------+------------+---------------
 Wireless Mouse Pro      | Accessories | 1            | 5.0        | 20
 Tablet Air              | Electronics | 1            | 5.0        | 12
 UltraBook Pro 15        | Electronics | 1            | 5.0        | 15
 Wireless Headphones Pro | Audio       | 1            | 4.0        | 8
(4 rows)

[0;32m✅ Success[0m


[0;32m═══════════════════════════════════════════════════════════════[0m
[0;32m  PHASE 5: Views and Materialized Views (Analytics)[0m
[0;32m═══════════════════════════════════════════════════════════════[0m

[0;36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━[0m
[1;33m📝 Creating virtual VIEW: customer_order_summary[0m
[0;34mSQL:[0m CREATE VIEW customer_order_summary AS
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
GROUP BY c.customer_id, c.first_name, c.last_name, c.email, c.loyalty_points;

OK
[0;32m✅ Success[0m

[0;36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━[0m
[1;33m📝 Querying virtual view[0m
[0;34mSQL:[0m SELECT * FROM customer_order_summary ORDER BY lifetime_value DESC NULLS LAST;

 customer_id | customer_name  |       email       | loyalty_points | total_orders | lifetime_value | last_order_date 
-------------+----------------+-------------------+----------------+--------------+----------------+-----------------
 1002        | Bob Smith      | bob@example.com   | 137.0          | 1            | 620.38         | 1.7045856E12
 1001        | Alice Johnson  | alice@example.com | 320.0          | 1            | 1704.48        | 1.7044992E12
             | Carol Williams | carol@example.com | 200            | 0            | 0              | 0.0
             | David Brown    | david@example.com | 50             | 0            | 0              | 0.0
             | Eve Davis      | eve@example.com   | 300            | 0            | 0              | 0.0
(5 rows)

[0;32m✅ Success[0m

[0;36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━[0m
[1;33m📝 Creating MATERIALIZED VIEW: product_performance[0m
[0;34mSQL:[0m CREATE MATERIALIZED VIEW product_performance AS
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
GROUP BY p.product_id, p.name, p.category, p.price, p.stock_quantity;

OK
[0;32m✅ Success[0m

[0;36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━[0m
[1;33m📝 Querying materialized view before REFRESH: Top selling products[0m
[0;34mSQL:[0m SELECT 
    name,
    category,
    price,
    units_sold,
    revenue,
    avg_rating,
    review_count
FROM product_performance
ORDER BY revenue DESC;

          name           |  category   |  price  
-------------------------+-------------+---------
 Fast Charger 65W        | Accessories | 39.99
 HD Webcam               | Electronics | 79.99
 Wireless Mouse Pro      | Accessories | 59.99
 SmartPhone X            | Electronics | 899.99
 Bluetooth Speaker       | Audio       | 129.99
 Mechanical Keyboard RGB | Accessories | 149.99
 Tablet Air              | Electronics | 499.99
 Wireless Headphones Pro | Audio       | 249.99
 UltraBook Pro 15        | Electronics | 1299.99
 4K Monitor 27-inch      | Electronics | 399.99
(10 rows)

[0;32m✅ Success[0m

[0;36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━[0m
[1;33m📝 Populating materialized view with REFRESH[0m
[0;34mSQL:[0m REFRESH MATERIALIZED VIEW product_performance;

OK
[0;32m✅ Success[0m

[0;36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━[0m
[1;33m📝 Querying materialized view after REFRESH: Top selling products[0m
[0;34mSQL:[0m SELECT 
    name,
    category,
    price,
    units_sold,
    revenue,
    avg_rating,
    review_count
FROM product_performance
ORDER BY revenue DESC;

          name           |  category   |  price  
-------------------------+-------------+---------
 Fast Charger 65W        | Accessories | 39.99
 HD Webcam               | Electronics | 79.99
 Wireless Mouse Pro      | Accessories | 59.99
 SmartPhone X            | Electronics | 899.99
 Bluetooth Speaker       | Audio       | 129.99
 Mechanical Keyboard RGB | Accessories | 149.99
 Tablet Air              | Electronics | 499.99
 Wireless Headphones Pro | Audio       | 249.99
 UltraBook Pro 15        | Electronics | 1299.99
 4K Monitor 27-inch      | Electronics | 399.99
(10 rows)

[0;32m✅ Success[0m

[0;36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━[0m
[1;33m📝 Creating index on materialized view[0m
[0;34mSQL:[0m CREATE INDEX idx_product_perf_revenue ON product_performance(revenue);

CREATE INDEX
[0;32m✅ Success[0m

[0;36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━[0m
[1;33m📝 Creating MATERIALIZED VIEW: daily_sales_summary[0m
[0;34mSQL:[0m CREATE MATERIALIZED VIEW daily_sales_summary AS
SELECT 
    o.order_date,
    COUNT(o.order_id) AS order_count,
    SUM(o.subtotal) AS subtotal,
    SUM(o.tax) AS tax,
    SUM(o.shipping) AS shipping,
    SUM(o.total) AS total_revenue,
    AVG(o.total) AS avg_order_value
FROM orders o
GROUP BY o.order_date;

OK
[0;32m✅ Success[0m

[0;36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━[0m
[1;33m📝 Querying materialized view: Daily sales[0m
[0;34mSQL:[0m SELECT * FROM daily_sales_summary ORDER BY order_date DESC;

  order_date   | avg_order_value | order_count | shipping | subtotal | total_revenue |  tax  
---------------+-----------------+-------------+----------+----------+---------------+-------
 1704585600000 | 620.38          | 1           | 10.0     | 559.98   | 620.38        | 50.4
 1704499200000 | 1704.48         | 1           | 15.0     | 1549.98  | 1704.48       | 139.5
(2 rows)

[0;32m✅ Success[0m


[1;33mAdding more data and refreshing materialized views...[0m

[0;36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━[0m
[1;33m📝 Adding new order[0m
[0;34mSQL:[0m INSERT INTO orders (order_id, customer_id, order_date, status, payment_method, subtotal, tax, shipping, total, shipping_address)
VALUES (5003, 1003, 1704844800000, 'pending', 'credit_card', 899.99, 81.00, 12.00, 992.99, '789 Pine Rd, San Francisco, CA 94102');

INSERT 0 1
[0;32m✅ Success[0m

[0;36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━[0m
[1;33m📝 Adding order item[0m
[0;34mSQL:[0m INSERT INTO order_items (order_item_id, order_id, product_id, quantity, unit_price, discount, line_total)
VALUES (10005, 5003, 2002, 1, 899.99, 0.00, 899.99);

INSERT 0 1
[0;32m✅ Success[0m

[0;36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━[0m
[1;33m📝 Updating inventory[0m
[0;34mSQL:[0m UPDATE products SET stock_quantity = stock_quantity - 1 WHERE product_id = 2002;

UPDATE 1
[0;32m✅ Success[0m

[0;36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━[0m
[1;33m📝 Refreshing materialized view: product_performance[0m
[0;34mSQL:[0m REFRESH MATERIALIZED VIEW product_performance;

OK
[0;32m✅ Success[0m

[0;36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━[0m
[1;33m📝 Refreshing materialized view: daily_sales_summary[0m
[0;34mSQL:[0m REFRESH MATERIALIZED VIEW daily_sales_summary;

OK
[0;32m✅ Success[0m

[0;36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━[0m
[1;33m📝 Verifying refreshed data for SmartPhone X[0m
[0;34mSQL:[0m SELECT * FROM product_performance WHERE product_id = 2002;

 units_sold | revenue | price  | product_id | avg_rating |     name     | review_count |  category   | stock_quantity 
------------+---------+--------+------------+------------+--------------+--------------+-------------+----------------
            |         | 899.99 | 2002       |            | SmartPhone X |              | Electronics | 99.0
(1 row)

[0;32m✅ Success[0m


[0;32m═══════════════════════════════════════════════════════════════[0m
[0;32m  PHASE 6: Advanced Features[0m
[0;32m═══════════════════════════════════════════════════════════════[0m

[0;36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━[0m
[1;33m📝 Query: Product profitability with arithmetic expressions[0m
[0;34mSQL:[0m SELECT 
    name,
    price,
    cost,
    price - cost AS profit,
    ROUND((price - cost) / price * 100, 2) AS profit_margin_pct,
    stock_quantity * price AS inventory_value
FROM products
WHERE stock_quantity > 0
ORDER BY profit DESC
LIMIT 5;

          name           |  price  |  cost  |       profit       | profit_margin_pct | inventory_value 
-------------------------+---------+--------+--------------------+-------------------+-----------------
 Fast Charger 65W        | 39.99   | 19.99  | 20.000000000000004 | 50.01             | 19995.0
 UltraBook Pro 15        | 1299.99 | 899.99 | 400.0              | 30.77             | 63699.51
 Mechanical Keyboard RGB | 149.99  | 79.99  | 70.00000000000001  | 46.67             | 22498.5
 HD Webcam               | 79.99   | 39.99  | 39.99999999999999  | 50.01             | 9598.8
 Wireless Headphones Pro | 249.99  | 149.99 | 100.0              | 40.0              | 49748.01
(5 rows)

[0;32m✅ Success[0m

[0;36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━[0m
[1;33m📝 Query: Math functions demonstration[0m
[0;34mSQL:[0m SELECT 
    name,
    price,
    ROUND(price, 0) AS rounded_price,
    CEIL(price) AS ceiling_price,
    FLOOR(price) AS floor_price,
    POWER(price, 2) AS price_squared,
    SQRT(price) AS price_sqrt
FROM products
WHERE category = 'Electronics'
LIMIT 3;

       name       |  price  | rounded_price | ceiling_price | floor_price |   price_squared   |     price_sqrt     
------------------+---------+---------------+---------------+-------------+-------------------+--------------------
 UltraBook Pro 15 | 1299.99 | 1300.0        | 1300.0        | 1299.0      | 1689974.0001      | 36.05537407932415
 SmartPhone X     | 899.99  | 900.0         | 900.0         | 899.0       | 809982.0001000001 | 29.999833332870367
 Tablet Air       | 499.99  | 500.0         | 500.0         | 499.0       | 249990.0001       | 22.360456167082102
(3 rows)

[0;32m✅ Success[0m

[0;36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━[0m
[1;33m📝 Query: String concatenation[0m
[0;34mSQL:[0m SELECT 
    customer_id,
    first_name || ' ' || last_name AS full_name,
    email,
    city || ', ' || state AS location
FROM customers
ORDER BY last_name, first_name;

 customer_id |   full_name    |       email       |     location      
-------------+----------------+-------------------+-------------------
 1004        | David Brown    | david@example.com | Los Angeles, CA
 1005        | Eve Davis      | eve@example.com   | San Diego, CA
 1001        | Alice Johnson  | alice@example.com | Seattle, WA
 1002        | Bob Smith      | bob@example.com   | Portland, OR
 1003        | Carol Williams | carol@example.com | San Francisco, CA
(5 rows)

[0;32m✅ Success[0m

[0;36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━[0m
[1;33m📝 Query: CASE expression for stock status[0m
[0;34mSQL:[0m SELECT 
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
ORDER BY stock_quantity;

          name           | stock_quantity | reorder_level | stock_status 
-------------------------+----------------+---------------+--------------
 4K Monitor 27-inch      | 40             | 10            | IN STOCK
 HD Webcam               | 120            | 20            | IN STOCK
 Mechanical Keyboard RGB | 150            | 25            | IN STOCK
 Bluetooth Speaker       | 180            | 30            | IN STOCK
 UltraBook Pro 15        | 49.0           | 10            | IN STOCK
 Wireless Headphones Pro | 199.0          | 30            | IN STOCK
 Wireless Mouse Pro      | 299.0          | 50            | IN STOCK
 Fast Charger 65W        | 500            | 100           | IN STOCK
 Tablet Air              | 74.0           | 15            | IN STOCK
 SmartPhone X            | 99.0           | 20            | IN STOCK
(10 rows)

[0;32m✅ Success[0m

[0;36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━[0m
[1;33m📝 EXPLAIN: Query plan for filtered JOIN[0m
[0;34mSQL:[0m EXPLAIN SELECT 
    c.first_name,
    c.last_name,
    o.order_id,
    o.total
FROM customers c
INNER JOIN orders o ON c.customer_id = o.customer_id
WHERE o.status = 'shipped';

                                   QUERY PLAN                                   
--------------------------------------------------------------------------------
 Query: SELECT                                                                 +
     c.first_name,                                                             +
     c.last_name,                                                              +
     o.order_id,                                                               +
     o.total                                                                   +
 FROM customers c                                                              +
 INNER JOIN orders o ON c.customer_id = o.customer_id                          +
 WHERE o.status = 'shipped'
 
 ✅ Parse: SUCCESS
   SQL Kind: SELECT
 
 ✅ Validate: SUCCESS
 
 📋 Logical Plan (Before Optimization):
 
   LogicalProject(FIRST_NAME=[$2], LAST_NAME=[$3], ORDER_ID=[$12], TOTAL=[$20])
     LogicalFilter(condition=[=($15, 'shipped')])
       LogicalJoin(condition=[=($0, $13)], joinType=[inner])
         LogicalTableScan(table=[[public, customers]])
         LogicalTableScan(table=[[public, orders]])
 
 ⚡ Optimized Plan (After Cost-Based Optimization):
 
   LogicalProject(FIRST_NAME=[$2], LAST_NAME=[$3], ORDER_ID=[$12], TOTAL=[$20])
     LogicalFilter(condition=[=($15, 'shipped')])
       LogicalJoin(condition=[=($0, $13)], joinType=[inner])
         LogicalTableScan(table=[[public, customers]])
         LogicalTableScan(table=[[public, orders]])
 
 💰 Cost Estimates:
   Rows: 22500.00
   Selectivity: 0.1500
 
 🔧 Optimizer Configuration:
   Cost-Based Optimizer: ENABLED
   Planner: Apache Calcite VolcanoPlanner
   Rules: Filter Pushdown, Index Selection, Join Reordering
 
(32 rows)

[0;32m✅ Success[0m

[0;36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━[0m
[1;33m📝 EXPLAIN ANALYZE: Execution plan with statistics[0m
[0;34mSQL:[0m EXPLAIN ANALYZE SELECT 
    product_id,
    name,
    price
FROM products
WHERE category = 'Electronics'
ORDER BY price DESC
LIMIT 5;

                         QUERY PLAN                         
------------------------------------------------------------
 Query: SELECT                                             +
     product_id,                                           +
     name,                                                 +
     price                                                 +
 FROM products                                             +
 WHERE category = 'Electronics'                            +
 ORDER BY price DESC                                       +
 LIMIT 5
 
 ✅ Parse: SUCCESS
   SQL Kind: ORDER_BY
 
 ✅ Validate: SUCCESS
 
 📋 Logical Plan (Before Optimization):
 
   LogicalSort(sort0=[$2], dir0=[DESC], fetch=[5])
     LogicalProject(PRODUCT_ID=[$0], NAME=[$2], PRICE=[$5])
       LogicalFilter(condition=[=($4, 'Electronics')])
         LogicalTableScan(table=[[public, products]])
 
 ⚡ Optimized Plan (After Cost-Based Optimization):
 
   LogicalSort(sort0=[$2], dir0=[DESC], fetch=[5])
     LogicalProject(PRODUCT_ID=[$0], NAME=[$2], PRICE=[$5])
       LogicalFilter(condition=[=($4, 'Electronics')])
         LogicalTableScan(table=[[public, products]])
 
 💰 Cost Estimates:
   Rows: 5.00
   Selectivity: 0.1500
 
 ⏱️  Execution Statistics:
 
   Execution Time: 3.374 ms
   Rows Returned: 5
   Memory Used: 505.52 KB
 
 🔧 Optimizer Configuration:
   Cost-Based Optimizer: ENABLED
   Planner: Apache Calcite VolcanoPlanner
   Rules: Filter Pushdown, Index Selection, Join Reordering
 
(36 rows)

[0;32m✅ Success[0m


[0;32m═══════════════════════════════════════════════════════════════[0m
[0;32m  PHASE 7: Data Modification (UPDATE, DELETE)[0m
[0;32m═══════════════════════════════════════════════════════════════[0m

[0;36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━[0m
[1;33m📝 UPDATE: 10% price increase for accessories[0m
[0;34mSQL:[0m UPDATE products 
SET price = price * 1.10 
WHERE category = 'Accessories';

UPDATE 3
[0;32m✅ Success[0m

[0;36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━[0m
[1;33m📝 Verifying price updates[0m
[0;34mSQL:[0m SELECT name, category, price FROM products WHERE category = 'Accessories';

          name           |  category   |       price        
-------------------------+-------------+--------------------
 Fast Charger 65W        | Accessories | 43.989000000000004
 Mechanical Keyboard RGB | Accessories | 164.98900000000003
 Wireless Mouse Pro      | Accessories | 65.989
(3 rows)

[0;32m✅ Success[0m

[0;36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━[0m
[1;33m📝 UPDATE: Bonus points for customers with delivered orders[0m
[0;34mSQL:[0m UPDATE customers 
SET loyalty_points = loyalty_points + 50 
WHERE customer_id IN (
    SELECT DISTINCT customer_id 
    FROM orders 
    WHERE status = 'delivered'
);

UPDATE 0
[0;32m✅ Success[0m

[0;36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━[0m
[1;33m📝 DELETE: Removing unhelpful low-rated reviews[0m
[0;34mSQL:[0m DELETE FROM reviews WHERE rating < 3 AND helpful_count = 0;

DELETE 0
[0;32m✅ Success[0m


[0;32m═══════════════════════════════════════════════════════════════[0m
[0;32m  PHASE 8: Analytics & Reporting[0m
[0;32m═══════════════════════════════════════════════════════════════[0m

[0;36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━[0m
[1;33m📝 Analytics: Revenue by product category[0m
[0;34mSQL:[0m SELECT 
    p.category,
    COUNT(DISTINCT oi.order_id) AS orders,
    SUM(oi.quantity) AS units_sold,
    SUM(oi.line_total) AS revenue,
    AVG(oi.unit_price) AS avg_price
FROM order_items oi
INNER JOIN products p ON oi.product_id = p.product_id
GROUP BY p.category
ORDER BY revenue DESC;

  category   | orders | units_sold |      revenue       |     avg_price     
-------------+--------+------------+--------------------+-------------------
 Electronics | 3      | 3          | 2699.9700000000003 | 899.9900000000001
 Audio       | 1      | 1          | 249.99             | 249.99
 Accessories | 1      | 1          | 59.99              | 59.99
(3 rows)

[0;32m✅ Success[0m

[0;36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━[0m
[1;33m📝 Analytics: Customer segmentation by spending[0m
[0;34mSQL:[0m SELECT 
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
ORDER BY avg_spent DESC;

 customer_tier | customer_count | avg_spent | total_revenue 
---------------+----------------+-----------+---------------
 New           | 4              | 0.0       | 0.0
(1 row)

[0;32m✅ Success[0m

[0;36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━[0m
[1;33m📝 Analytics: Inventory report by category[0m
[0;34mSQL:[0m SELECT 
    category,
    COUNT(*) AS product_count,
    SUM(stock_quantity) AS total_units,
    SUM(stock_quantity * price) AS inventory_value,
    SUM(CASE WHEN stock_quantity <= reorder_level THEN 1 ELSE 0 END) AS low_stock_count
FROM products
GROUP BY category
ORDER BY inventory_value DESC;

  category   | product_count | total_units | inventory_value | low_stock_count 
-------------+---------------+-------------+-----------------+-----------------
 Accessories | 3             | 949.0       | 0               | 0
 Electronics | 5             | 382.0       | 0               | 0
 Audio       | 2             | 379.0       | 0               | 0
(3 rows)

[0;32m✅ Success[0m

[0;36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━[0m
[1;33m📝 Analytics: Order status distribution[0m
[0;34mSQL:[0m SELECT 
    status,
    COUNT(*) AS order_count,
    SUM(total) AS total_value,
    AVG(total) AS avg_order_value
FROM orders
GROUP BY status
ORDER BY order_count DESC;

   status   | order_count | total_value | avg_order_value 
------------+-------------+-------------+-----------------
 pending    | 1           | 992.99      | 992.99
 shipped    | 1           | 620.38      | 620.38
 processing | 1           | 1704.48     | 1704.48
(3 rows)

[0;32m✅ Success[0m


[0;32m═══════════════════════════════════════════════════════════════[0m
[0;32m  DEMO COMPLETE![0m
[0;32m═══════════════════════════════════════════════════════════════[0m

[0;36mSummary of demonstrated features:[0m
  ✅ DDL: CREATE TABLE, ALTER TABLE, CREATE INDEX, CREATE SEQUENCE
  ✅ Data Types: BIGINT, TEXT, INT, DECIMAL, ENUM, ARRAY
  ✅ Constraints: PRIMARY KEY, FOREIGN KEY, UNIQUE, NOT NULL, DEFAULT
  ✅ DML: INSERT, UPDATE, DELETE
  ✅ Transactions: BEGIN, COMMIT with ACID guarantees
  ✅ Queries: SELECT, WHERE, ORDER BY, LIMIT
  ✅ JOINs: INNER JOIN, LEFT JOIN, multi-way JOINs
  ✅ Aggregations: COUNT, SUM, AVG, MIN, MAX, GROUP BY, HAVING
  ✅ Subqueries: Scalar and table subqueries
  ✅ Expressions: Arithmetic, string concatenation, CASE
  ✅ Functions: Math functions (ROUND, CEIL, FLOOR, POWER, SQRT)
  ✅ VIEWs: Virtual views with query rewriting
  ✅ MATERIALIZED VIEWs: Pre-computed results with REFRESH
  ✅ Indexes: Single and multi-column indexes, indexes on materialized views
  ✅ Query Planning: EXPLAIN and EXPLAIN ANALYZE
  ✅ Analytics: Complex reporting queries

[1;33m📊 Database Statistics:[0m
[0;36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━[0m
[1;33m📝 Final row counts[0m
[0;34mSQL:[0m SELECT 
    'Customers' AS table_name, COUNT(*) AS row_count FROM customers
    UNION ALL
    SELECT 'Products', COUNT(*) FROM products
    UNION ALL
    SELECT 'Orders', COUNT(*) FROM orders
    UNION ALL
    SELECT 'Order Items', COUNT(*) FROM order_items
    UNION ALL
    SELECT 'Reviews', COUNT(*) FROM reviews;

 row_count | table_name  
-----------+-------------
 5         | Customers
 10        | Products
 3         | Orders
 5         | Order Items
 4         | Reviews
(5 rows)

[0;32m✅ Success[0m


[0;32m🎉 Demo completed successfully![0m

