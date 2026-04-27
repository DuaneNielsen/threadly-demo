-- Seed Threadly catalog.
-- Explicit IDs so /products/7 (the Teapot) is stable across restarts
-- (SQLite AUTOINCREMENT doesn't reset after DELETE, unlike in-memory H2).
DELETE FROM products;

INSERT INTO products (id, name, description, image_url, price, original_price, stock, category, color, size) VALUES
(1, 'Segfault', 'Midweight combed cotton. No graphics, no glitches. Just a tee.', '/images/classic-crew.png', 24.00, 30.00, 120, 'tees', 'black', 'M'),
(2, 'Stack Trace', 'Tri-blend raglan with heathered finish. Reads like a good error log.', '/images/vintage-racer.png', 32.00, 40.00, 48, 'tees', 'heather', 'L'),
(3, 'Heisenbug', 'Relaxed fit with chest pocket. Present until you look for it.', '/images/pocket-tee.png', 22.00, 28.00, 200, 'tees', 'green', 'M'),
(4, 'Race Condition', 'Screen-printed hero piece. First come, first served.', '/images/thread-drop.png', 38.00, 45.00, 30, 'graphics', 'white', 'L'),
(5, 'Deadlock', 'Screen-printed, 100% cotton. Two threads enter, nobody leaves.', '/images/thread-pool.png', 35.00, 45.00, 60, 'graphics', 'blue', 'M'),
(6, 'Kernel Panic', '4-button waffle-knit henley. For when everything else has crashed.', '/images/henley.png', 48.00, 58.00, 42, 'long-sleeve', 'gray', 'L'),
(7, 'I''m a Teapot', 'HTTP 418. Free with newsletter signup. Short and stout.', '/images/signup-freebie.png', 0.00, 0.00, 500, 'promo', 'yellow', 'M');
