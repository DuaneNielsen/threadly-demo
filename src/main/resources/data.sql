-- Seed Threadly catalog
DELETE FROM products;

INSERT INTO products (name, description, image_url, price, original_price, stock, category, color, size) VALUES
('Classic Crew Tee', 'Midweight combed cotton crew neck. Runs true to size.', '/images/classic-crew.png', 24.00, 30.00, 120, 'tees', 'black', 'M'),
('Vintage Racer', 'Tri-blend baseball tee with raglan sleeves. Heathered finish.', '/images/vintage-racer.png', 32.00, 40.00, 48, 'tees', 'heather', 'L'),
('Pocket Tee', 'Relaxed fit with chest pocket. Soft-washed for day-one comfort.', '/images/pocket-tee.png', 22.00, 28.00, 200, 'tees', 'green', 'M'),
('Graphic - Thread Drop', 'Screen-printed hero piece. Limited run.', '/images/thread-drop.png', 38.00, 45.00, 30, 'graphics', 'white', 'L'),
('Graphic - Thread Pool', 'Screen-printed, 100% cotton. Short sleeve.', '/images/thread-pool.png', 35.00, 45.00, 60, 'graphics', 'blue', 'M'),
('Long Sleeve Henley', '4-button henley in waffle knit. Cold-weather staple.', '/images/henley.png', 48.00, 58.00, 42, 'long-sleeve', 'gray', 'L'),
('Signup Freebie', 'Complimentary tee for newsletter signups. No purchase required.', '/images/signup-freebie.png', 0.00, 0.00, 500, 'promo', 'yellow', 'M');
