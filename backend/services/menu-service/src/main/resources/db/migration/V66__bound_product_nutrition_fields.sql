-- Calories, weight and estimated preparation time were created in V62 with only a lower bound. The Menu
-- contract now bounds them: calories 0..10000 kcal, weight above 0 and up to 10000 g, preparation 0..240 min.
-- The constraint names are kept. Existing rows are neither deleted nor converted: if a row violates a new bound
-- the whole migration fails (PostgreSQL DDL is transactional, so nothing is left half applied) and the row
-- has to be reviewed by a person. The columns are still optional: NULL means unknown.
ALTER TABLE products
    DROP CONSTRAINT ck_products_weight_grams,
    DROP CONSTRAINT ck_products_calories,
    DROP CONSTRAINT ck_products_prep_minutes;

ALTER TABLE products
    ADD CONSTRAINT ck_products_weight_grams
        CHECK (weight_grams IS NULL OR (weight_grams > 0 AND weight_grams <= 10000)),
    ADD CONSTRAINT ck_products_calories
        CHECK (calories IS NULL OR (calories >= 0 AND calories <= 10000)),
    ADD CONSTRAINT ck_products_prep_minutes
        CHECK (estimated_prep_minutes IS NULL OR (estimated_prep_minutes >= 0 AND estimated_prep_minutes <= 240));
